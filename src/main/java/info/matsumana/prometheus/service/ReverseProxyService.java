package info.matsumana.prometheus.service;

import static com.linecorp.armeria.common.HttpMethod.GET;

import java.util.concurrent.CompletableFuture;
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

    public ReverseProxyService(PrometheusMeterRegistry registry) {
        this.registry = registry;
        httpClient = newHttpClient();
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

    private Function<Client<HttpRequest, HttpResponse>, CircuitBreakerHttpClient> newCircuitBreakerDecorator() {
        return CircuitBreakerHttpClient.newPerHostDecorator(
                name -> CircuitBreaker.builder("k8s-reverse-proxy" + '_' + name)
                                      .listener(new MetricCollectingCircuitBreakerListener(registry))
                                      .build(),
                CircuitBreakerStrategy.onServerErrorStatus());
    }

    @Get("regex:^/api/(?<actualUri>.*)$")
    public CompletableFuture<HttpResponse> proxyApi(@Param String actualUri) {
        log.info("actual URI=[{}]", actualUri);

        final String k8sToken = System.getenv(ENV_KUBERNETES_TOKEN);
        final RequestHeaders headers = RequestHeaders.of(GET, "/api/" + actualUri,
                                                         AUTHORIZATION_HEADER_KEY,
                                                         AUTHORIZATION_HEADER_VALUE + ' ' + k8sToken);
        final CompletableFuture<AggregatedHttpResponse> response = httpClient.execute(headers)
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
