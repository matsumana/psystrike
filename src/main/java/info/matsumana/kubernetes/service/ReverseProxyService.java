package info.matsumana.kubernetes.service;

import static com.linecorp.armeria.common.HttpMethod.GET;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
import com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

import info.matsumana.kubernetes.decorator.MetricCollectingDecorator;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;

@LoggingDecorator(requestLogLevel = LogLevel.DEBUG)
@MetricCollectingDecorator
@Slf4j
public class ReverseProxyService {
    private static final String ENV_KUBERNETES_API_SERVER = "KUBERNETES_API_SERVER";
    private static final String ENV_KUBERNETES_TOKEN = "KUBERNETES_TOKEN";
    private static final String KUBERNETES_DEFAULT_API_SERVER = "kubernetes.default.svc.cluster.local";
    private static final String AUTHORIZATION_HEADER_KEY = "Authorization";
    private static final String AUTHORIZATION_HEADER_VALUE = "Bearer";
    private static final int TIMEOUT_SECONDS_BUFFER = 10;
    private static final int CLIENT_MAX_RESPONSE_LENGTH_BYTE = 100 * 1024 * 1024;

    private final PrometheusMeterRegistry registry;
    private final ClientFactory clientFactory;

    // TODO Remove unused clients
    private final Map<String, HttpClient> httpClients = new ConcurrentHashMap<>();

    public ReverseProxyService(PrometheusMeterRegistry registry) {
        this.registry = registry;
        clientFactory = new ClientFactoryBuilder().meterRegistry(registry)
                                                  .build();
    }

    @Get("regex:^/api/(?<actualUri>.*)$")
    public CompletableFuture<HttpResponse> proxyK8sApi(ServiceRequestContext ctx,
                                                       HttpParameters params,
                                                       @Param String actualUri) {
        log.debug("params=[{}]", params);
        log.debug("actual URI=[{}]", actualUri);

        final boolean watch = params.getBoolean("watch", false);
        final int timeoutSeconds = params.getInt("timeoutSeconds", 0);
        if (watch & timeoutSeconds > 0) {
            ctx.setRequestTimeout(Duration.ofSeconds(timeoutSeconds + TIMEOUT_SECONDS_BUFFER));
        }

        final String queryString = StreamSupport.stream(params.spliterator(), false)
                                                .map(entry -> entry.getKey() + '=' + entry.getValue())
                                                .collect(Collectors.joining("&"));
        final String separator;
        if (!queryString.isEmpty()) {
            separator = "?";
        } else {
            separator = "";
        }

        final String k8sToken = System.getenv(ENV_KUBERNETES_TOKEN);
        final RequestHeaders headers = RequestHeaders.of(GET, "/api/" + actualUri + separator + queryString,
                                                         AUTHORIZATION_HEADER_KEY,
                                                         AUTHORIZATION_HEADER_VALUE + ' ' + k8sToken);
        final String apiServer = System.getenv(ENV_KUBERNETES_API_SERVER) != null ?
                                 System.getenv(ENV_KUBERNETES_API_SERVER) :
                                 KUBERNETES_DEFAULT_API_SERVER;
        final HttpClient httpClient = newH2HttpClientForApiServers(apiServer, 443);

        return handleRequest(headers, httpClient);
    }

    @Get("regex:^/apiservers/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    public CompletableFuture<HttpResponse> proxyApiServerMetrics(@Param String host,
                                                                 @Param int port,
                                                                 @Param String actualUri) {
        log.debug("host=[{}]", host);
        log.debug("port=[{}]", port);
        log.debug("actual URI=[{}]", actualUri);

        final String k8sToken = System.getenv(ENV_KUBERNETES_TOKEN);
        final RequestHeaders headers = RequestHeaders.of(GET, actualUri,
                                                         AUTHORIZATION_HEADER_KEY,
                                                         AUTHORIZATION_HEADER_VALUE + ' ' + k8sToken);
        final HttpClient client = newH2HttpClientForApiServers(host, port);

        return handleRequest(headers, client);
    }

    @Get("regex:^/pods/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    public CompletableFuture<HttpResponse> proxyPodMetrics(@Param String host,
                                                           @Param int port,
                                                           @Param String actualUri) {
        log.debug("host=[{}]", host);
        log.debug("port=[{}]", port);
        log.debug("actual URI=[{}]", actualUri);

        final RequestHeaders headers = RequestHeaders.of(GET, actualUri);
        final HttpClient client = newH1HttpClientForPods(host, port);

        return handleRequest(headers, client);
    }

    private HttpClient newH2HttpClientForApiServers(String host, int port) {
        return httpClients.computeIfAbsent(host, key ->
                new HttpClientBuilder(String.format("h2://%s:%d/", host, port))
                        .factory(clientFactory)
                        .maxResponseLength(CLIENT_MAX_RESPONSE_LENGTH_BYTE)
                        .responseTimeout(Duration.ofMinutes(10))  // TODO make const and link to k8s source code
                        .writeTimeout(Duration.ofMinutes(10))     // TODO make const and link to k8s source code
                        .decorator(MetricCollectingClient.newDecorator(
                                MeterIdPrefixFunction.ofDefault("armeria.client")
                                                     .withTags("server", String.format("%s:%d", host, port))))
                        .decorator(newCircuitBreakerDecorator(host))
                        .decorator(LoggingClient.newDecorator())
                        .build());
    }

    private HttpClient newH1HttpClientForPods(String host, int port) {
        return httpClients.computeIfAbsent(host, key ->
                new HttpClientBuilder(String.format("h1c://%s:%d/", host, port))
                        .factory(clientFactory)
                        .decorator(LoggingClient.newDecorator())
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

    private static CompletableFuture<HttpResponse> handleRequest(RequestHeaders headers, HttpClient client) {
        final CompletableFuture<AggregatedHttpResponse> future = client.execute(headers)
                                                                       .aggregate();

        return future.handle((response, throwable) -> {
            if (throwable == null) {
                log.debug("response={}", response);

                return HttpResponse.of(response);
            } else {
                log.error("k8s API error occurred", throwable);

                return HttpResponse.ofFailure(throwable);
            }
        });
    }
}
