package info.matsumana.psystrike.service;

import static com.linecorp.armeria.common.HttpHeaderNames.ACCEPT_ENCODING;
import static com.linecorp.armeria.common.HttpHeaderNames.CONTENT_TYPE;
import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static com.linecorp.armeria.common.MediaTypeNames.JSON_SEQ;
import static com.linecorp.armeria.common.MediaTypeNames.JSON_UTF_8;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
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
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
import com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.spring.MeterIdPrefixFunctionFactory;

import info.matsumana.psystrike.config.KubernetesProperties;
import info.matsumana.psystrike.helper.AppVersionHelper;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.util.AsciiString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReverseProxyService {

    private static final String HTTP_HEADER_AUTHORIZATION_KEY = "Authorization";
    private static final String HTTP_HEADER_AUTHORIZATION_VALUE_PREFIX = "Bearer ";
    private static final int CLIENT_MAX_RESPONSE_LENGTH_BYTE = 100 * 1024 * 1024;
    private static final int TIMEOUT_BUFFER_SECONDS = 3;

    // In Prometheus, watch timeout is random in [minWatchTimeout, 2*minWatchTimeout]
    // https://github.com/prometheus/prometheus/blob/v2.14.0/vendor/k8s.io/client-go/tools/cache/reflector.go#L78-L80
    // https://github.com/prometheus/prometheus/blob/v2.14.0/vendor/k8s.io/client-go/tools/cache/reflector.go#L262
    private static final int RESPONSE_TIMEOUT_MINUTES = 10;

    private final KubernetesProperties kubernetesProperties;
    private final PrometheusMeterRegistry registry;
    private final ClientFactory clientFactory;
    private final AppVersionHelper appVersionHelper;

    // TODO Remove no longer used clients
    private final Map<String, WebClient> webClients = new ConcurrentHashMap<>();

    @Get("regex:^/api/(?<actualUri>.*)$")
    public HttpResponse proxyApiServer(ServiceRequestContext ctx, RequestHeaders orgRequestHeaders,
                                       QueryParams params,
                                       @Param String actualUri) {

        log.debug("proxyApiServer orgRequestHeaders={}", orgRequestHeaders);

        final var watch = Boolean.parseBoolean(params.get("watch", "false"));
        final var timeoutSeconds = params.getInt("timeoutSeconds", 0);
        final String uri = generateRequestUri(params, actualUri);

        // create new headers with auth token
        final var requestHeaders = newRequestHeadersForApiServers(orgRequestHeaders, uri);
        final var client = newH2WebClientForApiServers(kubernetesProperties.getApiServer(),
                                                       kubernetesProperties.getApiServerPort());
        final ResponseHeaders responseHeaders;
        final Flux<HttpData> dataStream;
        final HttpResponse httpResponse = client.execute(requestHeaders);

        if (watch && timeoutSeconds > 0) {
            // streaming request
            //
            // see also:
            // https://www.soscon.net/content/data/session/Day%201_1330_3.pdf
            // https://engineering.linecorp.com/ja/blog/reactive-streams-with-armeria-1/
            // https://engineering.linecorp.com/ja/blog/reactive-streams-with-armeria-2/

            responseHeaders = ResponseHeaders.of(OK, CONTENT_TYPE, JSON_SEQ);
            ctx.setRequestTimeout(Duration.ofSeconds(timeoutSeconds + TIMEOUT_BUFFER_SECONDS));
            dataStream = Flux.from(httpResponse)
                             .doOnError(throwable -> log.error("Can't proxy to a k8s API server", throwable))
                             .doOnNext(response -> {
                                 if (response instanceof HttpData) {
                                     final HttpData httpData = (HttpData) response;
                                     log.debug("streaming response={}", httpData.toStringUtf8());
                                 }
                             })
                             .map(response -> {
                                 if (response instanceof HttpHeaders) {
                                     return HttpData.empty();
                                 } else {
                                     final HttpData httpData = (HttpData) response;
                                     return HttpData.ofUtf8(httpData.toStringUtf8());
                                 }
                             })
                             .filter(httpData -> !httpData.isEmpty());
        } else {
            // initial request
            responseHeaders = ResponseHeaders.of(OK, CONTENT_TYPE, JSON_UTF_8);
            dataStream = Flux.from(Mono.fromFuture(httpResponse.aggregate()))
                             .doOnError(throwable -> log.error("Can't proxy to a k8s API server", throwable))
                             .map(response -> HttpData.ofUtf8(response.contentUtf8()))
                             .take(1);
        }

        return HttpResponse.of(Flux.concat(Flux.just(responseHeaders), dataStream));
    }

    @Get("regex:^/apiservers/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    public Mono<HttpResponse> proxyApiServerMetrics(ServiceRequestContext ctx, RequestHeaders orgRequestHeaders,
                                                    @Param String host, @Param int port,
                                                    @Param String actualUri) {

        log.debug("proxyApiServerMetrics orgRequestHeaders={}", orgRequestHeaders);

        // create new headers with auth token
        final var requestHeaders = newRequestHeadersForApiServers(orgRequestHeaders, actualUri);
        final var client = newH2WebClientForApiServers(host, port);

        return Mono.fromFuture(client.execute(requestHeaders)
                                     .aggregate())
                   .doOnSuccess(response -> mutateAdditionalResponseHeaders(ctx, response.headers()))
                   .doOnError(throwable -> log.error("Can't collect metrics from a k8s API server", throwable))
                   .map(AggregatedHttpResponse::toHttpResponse);
    }

    @Get("regex:^/pods/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    public Mono<HttpResponse> proxyPodMetrics(ServiceRequestContext ctx, RequestHeaders orgRequestHeaders,
                                              @Param String host, @Param int port, @Param String actualUri) {

        log.debug("proxyPodMetrics orgRequestHeaders={}", orgRequestHeaders);

        final var requestHeaders = newRequestHeadersForPods(orgRequestHeaders, actualUri);
        final var client = newH1WebClientForPods(host, port);

        return Mono.fromFuture(client.execute(requestHeaders)
                                     .aggregate())
                   .doOnSuccess(response -> mutateAdditionalResponseHeaders(ctx, response.headers()))
                   .doOnError(throwable -> log.error("Can't collect metrics from a pod", throwable))
                   .map(AggregatedHttpResponse::toHttpResponse);
    }

    private RequestHeaders newRequestHeadersForApiServers(RequestHeaders requestHeaders, String uri) {
        final String authHeaderValue =
                HTTP_HEADER_AUTHORIZATION_VALUE_PREFIX + kubernetesProperties.getBearerToken();
        return RequestHeaders.of(requestHeaders)
                             .toBuilder()
                             .removeAndThen(ACCEPT_ENCODING)
                             .set(USER_AGENT, generateRequestHeaderUserAgent())
                             .add(HTTP_HEADER_AUTHORIZATION_KEY, authHeaderValue)
                             .scheme(H2)
                             .path(uri)
                             .build();
    }

    private RequestHeaders newRequestHeadersForPods(RequestHeaders orgRequestHeaders, @Param String uri) {
        return RequestHeaders.of(orgRequestHeaders)
                             .toBuilder()
                             .removeAndThen(ACCEPT_ENCODING)
                             .set(USER_AGENT, generateRequestHeaderUserAgent())
                             .scheme(H1C)
                             .path(uri)
                             .build();
    }

    private WebClient newH2WebClientForApiServers(String host, int port) {
        return webClients.computeIfAbsent(host + ':' + port, key ->
                // TODO get scheme via URI
                WebClient.builder(String.format("%s://%s:%d/", H2.uriText(), host, port))
                         .factory(clientFactory)
                         .maxResponseLength(CLIENT_MAX_RESPONSE_LENGTH_BYTE)
                         .responseTimeout(Duration.ofMinutes(RESPONSE_TIMEOUT_MINUTES))
                         .decorator(newMetricsDecorator(host, port))
                         .decorator(newCircuitBreakerDecorator(host))
                         .build());
    }

    private WebClient newH1WebClientForPods(String host, int port) {
        return webClients.computeIfAbsent(host + ':' + port, key ->
                // TODO get scheme via URI
                WebClient.builder(String.format("%s://%s:%d/", H1C.uriText(), host, port))
                         .factory(clientFactory)
                         .decorator(newCircuitBreakerDecorator(""))
                         .build());
    }

    private static Function<? super HttpClient, MetricCollectingClient> newMetricsDecorator(String host,
                                                                                            int port) {

        final String type = "client";
        final String serviceName = ReverseProxyService.class.getSimpleName();
        final MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunctionFactory.ofDefault()
                                                                                        .get(type, serviceName);
        final String server = String.format("%s:%d", host, port);
        return MetricCollectingClient.newDecorator(meterIdPrefixFunction.withTags("server", server));
    }

    private Function<? super HttpClient, CircuitBreakerClient> newCircuitBreakerDecorator(
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

        return CircuitBreakerClient.newDecorator(circuitBreaker,
                                                 CircuitBreakerStrategy.onServerErrorStatus());
    }

    private String generateRequestUri(QueryParams params, String actualUri) {
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

    private static void mutateAdditionalResponseHeaders(ServiceRequestContext ctx,
                                                        ResponseHeaders responseHeaders) {
        ctx.mutateAdditionalResponseHeaders(
                entries -> responseHeaders.forEach((BiConsumer<AsciiString, String>) entries::add));
    }

    @VisibleForTesting
    String generateRequestHeaderUserAgent() {
        final String version = appVersionHelper.getVersion().getArtifactVersion();
        if (!Strings.isNullOrEmpty(version)) {
            return "psystrike/" + version;
        } else {
            return "";
        }
    }

    @VisibleForTesting
    static String generatePrefix(String prefix) {
        final String s = StringUtils.trimLeadingCharacter(
                StringUtils.trimTrailingCharacter(prefix, '/'),
                '/');
        return !s.isEmpty() ? '/' + s : "";
    }
}
