package info.matsumana.psystrike.service;

import static com.linecorp.armeria.common.HttpHeaderNames.ACCEPT_ENCODING;
import static com.linecorp.armeria.common.HttpHeaderNames.USER_AGENT;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Collections.singleton;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.LoggerFactory;
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
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerListener;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.logging.LoggingClient;
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

import hu.akarnokd.rxjava2.interop.SingleInterop;
import info.matsumana.psystrike.config.CleanupTimerProperties;
import info.matsumana.psystrike.config.KubernetesProperties;
import info.matsumana.psystrike.helper.AppVersionHelper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.netty.util.AsciiString;
import io.reactivex.Flowable;
import io.reactivex.Single;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private static final Duration RESPONSE_TIMEOUT_MINUTES = Duration.ofMinutes(10);

    private final Map<String, Pair<WebClient, LocalDateTime>> webClients = new ConcurrentHashMap<>();
    private final Timer webClientsCleanupTimer = new Timer("WebClientsCleanupTimer");

    private final KubernetesProperties kubernetesProperties;
    private final CleanupTimerProperties cleanupTimerProperties;
    private final MeterRegistry meterRegistry;
    private final ClientFactory clientFactory;
    private final AppVersionHelper appVersionHelper;
    private final Clock clock;

    @PostConstruct
    void postConstruct() {
        setupWebClientsCleanupTimer(webClients, clock, cleanupTimerProperties);
        setupMetrics(meterRegistry, webClients);
    }

    @Get("regex:^/api/(?<actualUri>.*)$")
    public HttpResponse proxyApiServer(ServiceRequestContext ctx, RequestHeaders orgRequestHeaders,
                                       QueryParams params, @Param String actualUri) {

        log.debug("proxyApiServer orgRequestHeaders={}", orgRequestHeaders);

        final var watch = Boolean.parseBoolean(params.get("watch", "false"));
        final var timeoutSeconds = params.getInt("timeoutSeconds", 0);
        final String uri = generateRequestUri(params, actualUri);

        // create new headers with auth token
        final var requestHeaders = newRequestHeadersForApiServers(orgRequestHeaders, uri);
        final var client = newH2WebClientForApiServers(kubernetesProperties.getApiServer(),
                                                       kubernetesProperties.getApiServerPort());
        final Flowable<HttpData> dataStream;
        final HttpResponse httpResponse = client.execute(requestHeaders);

        if (watch && timeoutSeconds > 0) {
            // Streaming request for k8s Service Discovery by Prometheus
            //
            // see also:
            // https://www.soscon.net/content/data/session/Day%201_1330_3.pdf
            // https://engineering.linecorp.com/en/blog/reactive-streams-armeria-1/
            // https://engineering.linecorp.com/en/blog/reactive-streams-armeria-2/
            // https://engineering.linecorp.com/ja/blog/reactive-streams-with-armeria-1/
            // https://engineering.linecorp.com/ja/blog/reactive-streams-with-armeria-2/

            ctx.setRequestTimeout(Duration.ofSeconds(timeoutSeconds + TIMEOUT_BUFFER_SECONDS));
            dataStream = Flowable.fromPublisher(httpResponse)
                                 .doOnError(throwable -> log.error("Can't proxy to a k8s API server",
                                                                   throwable))
                                 .doOnNext(response -> {
                                     if (response instanceof HttpHeaders) {
                                         final HttpHeaders httpHeaders = (HttpHeaders) response;
                                         log.debug("streaming response httpHeaders={}", httpHeaders);
                                     } else {
                                         final HttpData httpData = (HttpData) response;
                                         log.debug("streaming response httpData={}", httpData.toStringUtf8());
                                     }
                                 })
                                 .filter(response -> response instanceof HttpData)
                                 .map(response -> (HttpData) response);
        } else {
            dataStream = Flowable.fromPublisher(httpResponse)
                                 .doOnError(throwable -> log.error("Can't proxy to a k8s API server",
                                                                   throwable))
                                 .filter(response -> response instanceof HttpData)
                                 .map(response -> (HttpData) response);
        }

        final ResponseHeaders responseHeaders = ResponseHeaders.of(OK);
        return HttpResponse.of(Flowable.concat(Flowable.just(responseHeaders), dataStream));
    }

    @Get("regex:^/apiservers/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    public Single<HttpResponse> proxyApiServerMetrics(ServiceRequestContext ctx,
                                                      RequestHeaders orgRequestHeaders,
                                                      @Param String host, @Param int port,
                                                      @Param String actualUri) {

        log.debug("proxyApiServerMetrics orgRequestHeaders={}", orgRequestHeaders);

        // create new headers with auth token
        final var requestHeaders = newRequestHeadersForApiServers(orgRequestHeaders, actualUri);
        final var client = newH2WebClientForApiServers(host, port);

        return SingleInterop.fromFuture(client.execute(requestHeaders)
                                              .aggregate())
                            .doOnSuccess(response -> mutateAdditionalResponseHeaders(ctx, response.headers()))
                            .doOnError(throwable -> log.error("Can't collect metrics from a k8s API server",
                                                              throwable))
                            .map(AggregatedHttpResponse::toHttpResponse);
    }

    @Get("regex:^/pods/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    public Single<HttpResponse> proxyPodMetrics(ServiceRequestContext ctx, RequestHeaders orgRequestHeaders,
                                                @Param String host, @Param int port, @Param String actualUri) {

        log.debug("proxyPodMetrics orgRequestHeaders={}", orgRequestHeaders);

        final var requestHeaders = newRequestHeadersForPods(orgRequestHeaders, actualUri);
        final var client = newH1WebClientForPods(host, port);

        return SingleInterop.fromFuture(client.execute(requestHeaders)
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

    private RequestHeaders newRequestHeadersForPods(RequestHeaders orgRequestHeaders, String uri) {
        return RequestHeaders.of(orgRequestHeaders)
                             .toBuilder()
                             .removeAndThen(ACCEPT_ENCODING)
                             .set(USER_AGENT, generateRequestHeaderUserAgent())
                             .scheme(H1C)
                             .path(uri)
                             .build();
    }

    private WebClient newH2WebClientForApiServers(String host, int port) {
        final Pair<WebClient, LocalDateTime> pair =
                webClients.computeIfAbsent(host + ':' + port, key -> {
                    final LocalDateTime usedAt = LocalDateTime.now(clock);
                    final WebClient webClient =
                            WebClient.builder(String.format("%s://%s:%d/", H2.uriText(), host, port))
                                     .factory(clientFactory)
                                     .maxResponseLength(CLIENT_MAX_RESPONSE_LENGTH_BYTE)
                                     .responseTimeout(RESPONSE_TIMEOUT_MINUTES)
                                     .decorator(newCircuitBreakerDecorator(host))
                                     .decorator(newMetricsDecorator(host, port))
                                     .decorator(newLoggingClientDecorator())
                                     .build();
                    return MutablePair.of(webClient, usedAt);
                });
        pair.setValue(LocalDateTime.now(clock));
        return pair.getLeft();
    }

    private WebClient newH1WebClientForPods(String host, int port) {
        final Pair<WebClient, LocalDateTime> pair =
                webClients.computeIfAbsent(host + ':' + port, key -> {
                    final LocalDateTime usedAt = LocalDateTime.now(clock);
                    final WebClient webClient =
                            WebClient.builder(String.format("%s://%s:%d/", H1C.uriText(), host, port))
                                     .factory(clientFactory)
                                     .decorator(newCircuitBreakerDecorator(""))
                                     .decorator(newLoggingClientDecorator())
                                     .build();
                    return MutablePair.of(webClient, usedAt);
                });
        pair.setValue(LocalDateTime.now(clock));
        return pair.getLeft();
    }

    private static Function<? super HttpClient, MetricCollectingClient> newMetricsDecorator(String host,
                                                                                            int port) {
        final String serviceName = ReverseProxyService.class.getSimpleName();
        final MeterIdPrefixFunction meterIdPrefixFunction =
                MeterIdPrefixFunction.ofDefault("armeria.client")
                                     .withTags(singleton(Tag.of("service", serviceName)));
        final String server = String.format("%s:%d", host, port);

        return MetricCollectingClient.newDecorator(meterIdPrefixFunction.withTags("server", server));
    }

    private Function<? super HttpClient, CircuitBreakerClient> newCircuitBreakerDecorator(String hostname) {
        final CircuitBreakerBuilder builder;
        if (Strings.isNullOrEmpty(hostname)) {
            builder = CircuitBreaker.builder();
        } else {
            // with metrics
            builder = CircuitBreaker.builder("kube-apiserver_" + hostname)
                                    .listener(CircuitBreakerListener.metricCollecting(meterRegistry));
        }
        final CircuitBreaker circuitBreaker = builder.failureRateThreshold(0.1)
                                                     .minimumRequestThreshold(1)
                                                     .trialRequestInterval(Duration.ofSeconds(5))
                                                     .circuitOpenWindow(Duration.ofSeconds(10))
                                                     .counterSlidingWindow(Duration.ofSeconds(60))
                                                     .build();

        return CircuitBreakerClient.newDecorator(circuitBreaker,
                                                 CircuitBreakerRule.onServerErrorStatus());
    }

    private static Function<? super HttpClient, LoggingClient> newLoggingClientDecorator() {
        return LoggingClient.builder()
                            .logger(LoggerFactory.getLogger(ReverseProxyService.class))
                            .newDecorator();
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

    @VisibleForTesting
    void setupWebClientsCleanupTimer(Map<String, Pair<WebClient, LocalDateTime>> webClients,
                                     Clock clock, CleanupTimerProperties cleanupTimerProperties) {
        final TimerTask webClientsCleanupTask = new TimerTask() {
            @Override
            public void run() {
                final LocalDateTime now = LocalDateTime.now(clock);

                log.debug("before: webClients.size={}", webClients.size());

                webClients.forEach((host, pair) -> {
                    final LocalDateTime usedAt = pair.getRight();
                    final long diff = SECONDS.between(usedAt, now);
                    final long removeThreshold = cleanupTimerProperties.getRemoveThresholdSeconds();

                    log.debug("host={}, usedAt={}, now={} diff={}, removeThreshold={}",
                              host, usedAt, now, diff, removeThreshold);

                    if (diff > removeThreshold) {
                        log.debug("remove {} from webClients", host);
                        webClients.remove(host);
                    }
                });

                log.debug("after: webClients.size={}", webClients.size());
            }
        };

        final int delay = cleanupTimerProperties.getDelaySeconds() * 1000;
        final int period = cleanupTimerProperties.getPeriodSeconds() * 1000;
        webClientsCleanupTimer.scheduleAtFixedRate(webClientsCleanupTask, delay, period);
    }

    @VisibleForTesting
    void setupMetrics(MeterRegistry meterRegistry, Map<String, Pair<WebClient, LocalDateTime>> webClients) {
        Gauge.builder("psystrike.webclients", webClients::size)
             .description("Number of WebClients")
             .register(meterRegistry);
    }
}
