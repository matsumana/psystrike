package info.matsumana.prometheus.service;

import static com.linecorp.armeria.common.HttpMethod.GET;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
import com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

import info.matsumana.prometheus.decorator.MetricCollectingDecorator;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;

@LoggingDecorator(requestLogLevel = LogLevel.INFO)
@MetricCollectingDecorator
@Slf4j
public class ReverseProxyService {
    private static final String ENV_KUBERNETES_API_SERVER = "KUBERNETES_API_SERVER";
    private static final String ENV_KUBERNETES_TOKEN = "KUBERNETES_TOKEN";
    private static final String KUBERNETES_DEFAULT_API_SERVER = "kubernetes.default.svc.cluster.local";
    private static final String AUTHORIZATION_HEADER_KEY = "Authorization";
    private static final String AUTHORIZATION_HEADER_VALUE = "Bearer";

    private final PrometheusMeterRegistry registry;
    private final HttpClient httpClient;
    private final Map<String, HttpClient> httpClientsForPod = new ConcurrentHashMap<>();

    public ReverseProxyService(PrometheusMeterRegistry registry) {
        this.registry = registry;
        httpClient = newHttpClient();
    }

    @Get("regex:^/api/(?<actualUri>.*)$")
    public CompletableFuture<HttpResponse> proxyK8sApi(@Param String actualUri) {
        log.info("actual URI=[{}]", actualUri);

        final String k8sToken = System.getenv(ENV_KUBERNETES_TOKEN);
        final RequestHeaders headers = RequestHeaders.of(GET, "/api/" + actualUri,
                                                         AUTHORIZATION_HEADER_KEY,
                                                         AUTHORIZATION_HEADER_VALUE + ' ' + k8sToken);

        return handleRequest(headers, httpClient);
    }

    @Get("regex:^/apiserver-metrics/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    public CompletableFuture<HttpResponse> proxyApiServerMetrics(@Param String host,
                                                                 @Param int port,
                                                                 @Param String actualUri) {
        log.info("host=[{}]", host);
        log.info("port=[{}]", port);
        log.info("actual URI=[{}]", actualUri);

        final String k8sToken = System.getenv(ENV_KUBERNETES_TOKEN);
        final RequestHeaders headers = RequestHeaders.of(GET, actualUri,
                                                         AUTHORIZATION_HEADER_KEY,
                                                         AUTHORIZATION_HEADER_VALUE + ' ' + k8sToken);
        final HttpClient clientForPod = newHttpClientForPod("https", host, port);

        return handleRequest(headers, clientForPod);
    }

    @Get("regex:^/pod-metrics/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    public CompletableFuture<HttpResponse> proxyPodMetrics(@Param String host,
                                                           @Param int port,
                                                           @Param String actualUri) {
        log.info("host=[{}]", host);
        log.info("port=[{}]", port);
        log.info("actual URI=[{}]", actualUri);

        final RequestHeaders headers = RequestHeaders.of(GET, actualUri);
        final HttpClient clientForPod = newHttpClientForPod("http", host, port);

        return handleRequest(headers, clientForPod);
    }

    private HttpClient newHttpClient() {
        final String apiServer = System.getenv(ENV_KUBERNETES_API_SERVER) != null ?
                                 System.getenv(ENV_KUBERNETES_API_SERVER) :
                                 KUBERNETES_DEFAULT_API_SERVER;
        return new HttpClientBuilder(String.format("https://%s/", apiServer))
                .decorator(newCircuitBreakerDecorator())
                .decorator(LoggingClient.newDecorator())
                .build();
    }

    private HttpClient newHttpClientForPod(String scheme, String host, int port) {
        return httpClientsForPod.computeIfAbsent(host, key ->
                new HttpClientBuilder(String.format("%s://%s:%d/", scheme, host, port))
                        .decorator(newCircuitBreakerDecorator())
                        .decorator(LoggingClient.newDecorator())
                        .build());
    }

    private Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient> newCircuitBreakerDecorator() {
        return CircuitBreakerHttpClient.newPerHostDecorator(
                name -> CircuitBreaker.builder("k8s-reverse-proxy" + '_' + name)
                                      .listener(new MetricCollectingCircuitBreakerListener(registry))
                                      .build(),
                CircuitBreakerStrategy.onServerErrorStatus());
    }

    private static CompletableFuture<HttpResponse> handleRequest(RequestHeaders headers,
                                                                 HttpClient clientForPod) {
        final CompletableFuture<AggregatedHttpResponse> response = clientForPod
                .execute(headers)
                .aggregate();

        return response.handle((message, throwable) -> {
            if (throwable == null) {
                return HttpResponse.of(message);
            } else {
                log.error("k8s API error occurred", throwable);

                return HttpResponse.ofFailure(throwable);
            }
        });
    }
}
