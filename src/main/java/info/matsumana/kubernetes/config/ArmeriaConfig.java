package info.matsumana.kubernetes.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import info.matsumana.kubernetes.service.ReverseProxyService;
import io.micrometer.prometheus.PrometheusMeterRegistry;

@Configuration
public class ArmeriaConfig {

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        // Use BetterPrometheusNamingConvention
        return PrometheusMeterRegistries.newRegistry();
    }

    @Bean
    public ArmeriaServerConfigurator armeriaServerConfigurator(ReverseProxyService reverseProxyService) {
        return builder -> builder.annotatedService(reverseProxyService);
    }

    @Bean
    public ClientFactory clientFactory(PrometheusMeterRegistry registry) {
        return new ClientFactoryBuilder().meterRegistry(registry)
                                         .build();
    }
}
