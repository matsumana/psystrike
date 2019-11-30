package info.matsumana.kubernetes.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;

import info.matsumana.kubernetes.service.ReverseProxyService;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.AllArgsConstructor;

@Configuration
@AllArgsConstructor
public class ArmeriaConfig {

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
