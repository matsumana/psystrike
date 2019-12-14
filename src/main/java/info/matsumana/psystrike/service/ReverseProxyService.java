package info.matsumana.psystrike.service;

import static com.linecorp.armeria.common.HttpMethod.GET;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static io.reactivex.BackpressureStrategy.BUFFER;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
import com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;

import hu.akarnokd.rxjava2.interop.ObservableInterop;
import info.matsumana.psystrike.annotation.ProducesPrometheusMetrics;
import info.matsumana.psystrike.config.KubernetesProperties;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@AllArgsConstructor
@Slf4j
public class ReverseProxyService {

    private static final String AUTHORIZATION_HEADER_KEY = "Authorization";
    private static final String AUTHORIZATION_HEADER_VALUE = "Bearer";
    private static final int CLIENT_MAX_RESPONSE_LENGTH_BYTE = 100 * 1024 * 1024;
    private static final int TIMEOUT_SECONDS_BUFFER = 10;

    // In Prometheus, watch timeout is random in [minWatchTimeout, 2*minWatchTimeout]
    // https://github.com/prometheus/prometheus/blob/v2.14.0/vendor/k8s.io/client-go/tools/cache/reflector.go#L78-L80
    // https://github.com/prometheus/prometheus/blob/v2.14.0/vendor/k8s.io/client-go/tools/cache/reflector.go#L262
    private static final int RESPONSE_TIMEOUT_MIN = 10;
    private static final int WRITE_TIMEOUT_MIN = 10;

    private final KubernetesProperties kubernetesProperties;
    private final PrometheusMeterRegistry registry;
    private final ClientFactory clientFactory;

    // TODO Remove no longer used clients
    private final Map<String, WebClient> webClients = new ConcurrentHashMap<>();

    @Get("regex:^/api/(?<actualUri>.*)$")
    @ProducesJson
    public HttpResponse proxyApiServer(ServiceRequestContext ctx, HttpParameters params,
                                       @Param String actualUri) {
        final String uri = generateRequestUri(params, actualUri);
        final var requestHeader = RequestHeaders.of(GET, uri,
                                                    AUTHORIZATION_HEADER_KEY,
                                                    AUTHORIZATION_HEADER_VALUE + ' ' +
                                                    kubernetesProperties.getBearerToken());
        final var client = newH2WebClientForApiServers(kubernetesProperties.getApiServer(),
                                                       kubernetesProperties.getApiServerPort());
        final Flux<HttpData> dataStream = Flux.from(ObservableInterop.fromFuture(client.execute(requestHeader)
                                                                                       .aggregate())
                                                                     .toFlowable(BUFFER))
                                              .map(response -> HttpData.ofUtf8(response.contentUtf8()));
        final var responseHeader = ResponseHeaders.of(OK);

        final var watch = params.getBoolean("watch", false);
        final var timeoutSeconds = params.getInt("timeoutSeconds", 0);

        if (watch && timeoutSeconds > 0) {
            ctx.setRequestTimeout(Duration.ofSeconds(timeoutSeconds + TIMEOUT_SECONDS_BUFFER));
            return HttpResponse.of(Flux.concat(Flux.just(responseHeader), dataStream));
        } else {
            return HttpResponse.of(Flux.concat(Flux.just(responseHeader), dataStream.take(1)));
        }
    }

    @Get("regex:^/apiservers/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    @ProducesPrometheusMetrics
    public Mono<String> proxyApiServerMetrics(@Param String host, @Param int port, @Param String actualUri) {
        final var requestHeader = RequestHeaders.of(GET, actualUri,
                                                    AUTHORIZATION_HEADER_KEY,
                                                    AUTHORIZATION_HEADER_VALUE + ' ' +
                                                    kubernetesProperties.getBearerToken());
        final var client = newH2WebClientForApiServers(host, port);

        return Mono.fromFuture(client.execute(requestHeader)
                                     .aggregate())
                   .map(response -> response.contentUtf8());
    }

    @Get("regex:^/pods/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    @ProducesPrometheusMetrics
    public Mono<String> proxyPodMetrics(@Param String host, @Param int port, @Param String actualUri) {
        final var requestHeader = RequestHeaders.of(GET, actualUri);
        final var client = newH1WebClientForPods(host, port);

        return Mono.fromFuture(client.execute(requestHeader)
                                     .aggregate())
                   .map(response -> response.contentUtf8());
    }

    private WebClient newH2WebClientForApiServers(String host, int port) {
        return webClients.computeIfAbsent(host, key ->
                // TODO get scheme via URI
                WebClient.builder(String.format("%s://%s:%d/", H2.uriText(), host, port))
                         .factory(clientFactory)
                         .maxResponseLength(CLIENT_MAX_RESPONSE_LENGTH_BYTE)
                         .responseTimeout(Duration.ofMinutes(RESPONSE_TIMEOUT_MIN))
                         .writeTimeout(Duration.ofMinutes(WRITE_TIMEOUT_MIN))
                         .decorator(MetricCollectingClient.newDecorator(
                                 MeterIdPrefixFunction.ofDefault("armeria.client")
                                                      .withTags("server", String.format("%s:%d", host, port))))
                         .decorator(newCircuitBreakerDecorator(host))
                         .build());
    }

    private WebClient newH1WebClientForPods(String host, int port) {
        return webClients.computeIfAbsent(host, key ->
                // TODO get scheme via URI
                WebClient.builder(String.format("%s://%s:%d/", H1C.uriText(), host, port))
                         .factory(clientFactory)
                         .decorator(newCircuitBreakerDecorator(""))
                         .build());
    }

    private Function<? super HttpClient, CircuitBreakerHttpClient> newCircuitBreakerDecorator(
            String hostname) {
        final CircuitBreakerBuilder builder;
        if (Strings.isNullOrEmpty(hostname)) {
            builder = CircuitBreaker.builder();
        } else {
            // with metrics
            builder = CircuitBreaker.builder("kube-apiserver_" + hostname)
                                    .listener(new MetricCollectingCircuitBreakerListener(registry));
        }
        final CircuitBreaker circuitBreaker = builder.failureRateThreshold(0.1)
                                                     .minimumRequestThreshold(1)
                                                     .trialRequestInterval(Duration.ofSeconds(5))
                                                     .circuitOpenWindow(Duration.ofSeconds(10))
                                                     .counterSlidingWindow(Duration.ofSeconds(60))
                                                     .build();

        return CircuitBreakerHttpClient.newDecorator(circuitBreaker,
                                                     CircuitBreakerStrategy.onServerErrorStatus());
    }

    private String generateRequestUri(HttpParameters params, String actualUri) {
        final String queryString = StreamSupport.stream(params.spliterator(), false)
                                                .map(entry -> entry.getKey() + '=' + entry.getValue())
                                                .collect(Collectors.joining("&"));
        final String separator;
        if (!queryString.isEmpty()) {
            separator = "?";
        } else {
            separator = "";
        }

        return generatePrefix(kubernetesProperties.getApiUriPrefix()) +
               "/api/" + actualUri + separator + queryString;
    }

    @VisibleForTesting
    static String generatePrefix(String prefix) {
        final String s = StringUtils.trimLeadingCharacter(
                StringUtils.trimTrailingCharacter(prefix, '/'),
                '/');
        return !s.isEmpty() ? '/' + s : "";
    }
}
