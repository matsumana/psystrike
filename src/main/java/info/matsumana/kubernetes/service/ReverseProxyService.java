package info.matsumana.kubernetes.service;

import static info.matsumana.kubernetes.config.ArmeriaConfig.CLIENT_MAX_RESPONSE_LENGTH_BYTE;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.Builder;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

import info.matsumana.kubernetes.annotation.ProducesPrometheusMetrics;
import info.matsumana.kubernetes.config.KubernetesProperties;
import info.matsumana.kubernetes.decorator.MetricCollectingDecorator;
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

    private static final String AUTHORIZATION_HEADER_KEY = "Authorization";
    private static final String AUTHORIZATION_HEADER_VALUE = "Bearer";
    private static final int TIMEOUT_SECONDS_BUFFER = 10;

    private final KubernetesProperties kubernetesProperties;
    private final Builder webClientBuilder;

    // TODO Remove no longer used clients
    private final Map<String, WebClient> webClients = new ConcurrentHashMap<>();

    @Get("regex:^/api/(?<actualUri>.*)$")
    @ProducesJson
    public HttpResponse proxyK8sApi(ServiceRequestContext ctx, HttpParameters params, @Param String actualUri) {
        final var watch = params.getBoolean("watch", false);
        final var timeoutSeconds = params.getInt("timeoutSeconds", 0);
        if (watch & timeoutSeconds > 0) {
            ctx.setRequestTimeout(Duration.ofSeconds(timeoutSeconds + TIMEOUT_SECONDS_BUFFER));
        }

        final MultiValueMap<String, String> queryParams =
                StreamSupport.stream(params.spliterator(), false)
                             .collect(LinkedMultiValueMap::new,
                                      (map, entry) -> map.add(entry.getKey(), entry.getValue()),
                                      MultiValueMap::addAll);
        final var responseHeaders = ResponseHeaders.of(HttpStatus.OK);
        final Flux<HttpData> dataStream =
                newWebClient("h2", kubernetesProperties.getKubernetesApiServer(), 443)
                        .get()
                        .uri(uriBuilder -> uriBuilder.path("/api/" + actualUri)
                                                     .queryParams(queryParams)
                                                     .build())
                        .header(AUTHORIZATION_HEADER_KEY,
                                AUTHORIZATION_HEADER_VALUE + ' ' + kubernetesProperties.getKubernetesToken())
                        .retrieve()
                        .bodyToFlux(String.class)
                        .map(s -> Strings.isNullOrEmpty(s) ? "" : s)
                        .map(HttpData::ofUtf8);

        if (watch & timeoutSeconds > 0) {
            return HttpResponse.of(Flux.concat(Flux.just(responseHeaders), dataStream));
        } else {
            return HttpResponse.of(Flux.concat(Flux.just(responseHeaders), dataStream.take(1)));
        }
    }

    @Get("regex:^/apiservers/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    @ProducesPrometheusMetrics
    public Mono<String> proxyApiServerMetrics(@Param String host, @Param int port, @Param String actualUri) {
        return newWebClient("h2", host, port)
                .get()
                .uri(uriBuilder -> uriBuilder.path(actualUri)
                                             .build())
                .header(AUTHORIZATION_HEADER_KEY,
                        AUTHORIZATION_HEADER_VALUE + ' ' + kubernetesProperties.getKubernetesToken())
                .retrieve()
                .bodyToMono(String.class)
                .map(s -> Strings.isNullOrEmpty(s) ? "" : s);
    }

    @Get("regex:^/pods/(?<host>.*?)/(?<port>.*?)/(?<actualUri>.*)$")
    @ProducesPrometheusMetrics
    public Mono<String> proxyPodMetrics(@Param String host, @Param int port, @Param String actualUri) {
        return newWebClient("h1c", host, port)
                .get()
                .uri(uriBuilder -> uriBuilder.path(actualUri)
                                             .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(s -> Strings.isNullOrEmpty(s) ? "" : s);
    }

    private WebClient newWebClient(String scheme, String host, int port) {
        final var strategy =
                ExchangeStrategies.builder()
                                  .codecs(configurer -> configurer.defaultCodecs()
                                                                  .maxInMemorySize(
                                                                          CLIENT_MAX_RESPONSE_LENGTH_BYTE))
                                  .build();

        return webClients.computeIfAbsent(host, key -> {
            final var baseUrl = String.format("%s://%s:%d/", scheme, host, port);
            return webClientBuilder.baseUrl(baseUrl)
                                   .exchangeStrategies(strategy)
                                   .build();
        });
    }
}
