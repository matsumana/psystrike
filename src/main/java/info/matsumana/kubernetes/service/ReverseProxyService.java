package info.matsumana.kubernetes.service;

import static com.linecorp.armeria.common.HttpMethod.GET;
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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
import com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

import hu.akarnokd.rxjava2.interop.ObservableInterop;
import info.matsumana.kubernetes.annotation.ProducesPrometheusMetrics;
import info.matsumana.kubernetes.config.KubernetesProperties;
import info.matsumana.kubernetes.decorator.MetricCollectingDecorator;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@LoggingDecorator(requestLogLevel = LogLevel.DEBUG)
@MetricCollectingDecorator
@AllArgsConstructor
@Slf4j
public class ReverseProxyService {

    private static final int CLIENT_MAX_RESPONSE_LENGTH_BYTE = 100 * 1024 * 1024;

    // TODO make const and link to k8s source code
    private static final int RESPONSE_TIMEOUT_MIN = 10;
    private static final int WRITE_TIMEOUT_MIN = 10;

    private static final String AUTHORIZATION_HEADER_KEY = "Authorization";
    private static final String AUTHORIZATION_HEADER_VALUE = "Bearer";
    private static final int TIMEOUT_SECONDS_BUFFER = 10;

    private final KubernetesProperties kubernetesProperties;
    private final PrometheusMeterRegistry registry;
    private final ClientFactory clientFactory;

    // TODO Remove no longer used clients
    private final Map<String, HttpClient> httpClients = new ConcurrentHashMap<>();

    @Get("regex:^/api/(?<actualUri>.*)$")
    @ProducesJson
    public HttpResponse proxyApiServer(ServiceRequestContext ctx, HttpParameters params,
                                       @Param String actualUri) {
        final var watch = params.getBoolean("watch", false);
        final var timeoutSeconds = params.getInt("timeoutSeconds", 0);
        if (watch && timeoutSeconds > 0) {
            ctx.setRequestTimeout(Duration.ofSeconds(timeoutSeconds + TIMEOUT_SECONDS_BUFFER));
        }

        final String uri = createRequestUri(params, actualUri);
        final var requestHeader = RequestHeaders.of(GET, uri,
                                                    AUTHORIZATION_HEADER_KEY,
                                                    AUTHORIZATION_HEADER_VALUE + ' ' +
                                                    kubernetesProperties.getBearerToken());
        final var client = newH2HttpClientForApiServers(kubernetesProperties.getApiServer(),
                                                        kubernetesProperties.getApiServerPort());
        final Flux<HttpData> dataStream = Flux.from(ObservableInterop.fromFuture(client.execute(requestHeader)
                                                                                       .aggregate())
                                                                     .toFlowable(BUFFER))
                                              .map(response -> HttpData.ofUtf8(response.contentUtf8()));
        final var responseHeader = ResponseHeaders.of(HttpStatus.OK);

        if (watch && timeoutSeconds > 0) {
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
        final var client = newH2HttpClientForApiServers(host, port);

        return Mono.fromFuture(client.execute(requestHeader)
                                     .aggregate())
                   .map(response -> response.contentUtf8());
    }

    @Get("regex:^/pods/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    @ProducesPrometheusMetrics
    public Mono<String> proxyPodMetrics(@Param String host, @Param int port, @Param String actualUri) {
        final var requestHeader = RequestHeaders.of(GET, actualUri);
        final var client = newH1HttpClientForPods(host, port);

        return Mono.fromFuture(client.execute(requestHeader)
                                     .aggregate())
                   .map(response -> response.contentUtf8());
    }

    private HttpClient newH2HttpClientForApiServers(String host, int port) {
        return httpClients.computeIfAbsent(host, key ->
                new HttpClientBuilder(String.format("h2://%s:%d/", host, port))
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

    private HttpClient newH1HttpClientForPods(String host, int port) {
        return httpClients.computeIfAbsent(host, key ->
                new HttpClientBuilder(String.format("h1c://%s:%d/", host, port))
                        .factory(clientFactory)
                        .build());
    }

    private Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient> newCircuitBreakerDecorator(
            String hostname) {
        return CircuitBreakerHttpClient.newDecorator(
                CircuitBreaker.builder("kube-apiserver_" + hostname)
                              .listener(new MetricCollectingCircuitBreakerListener(registry))
                              .build(),
                CircuitBreakerStrategy.onServerErrorStatus());
    }

    private String createRequestUri(HttpParameters params, String actualUri) {
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
