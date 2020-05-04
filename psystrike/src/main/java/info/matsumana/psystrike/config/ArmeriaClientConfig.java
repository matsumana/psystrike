package info.matsumana.psystrike.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.client.ClientFactory;

import io.micrometer.prometheus.PrometheusMeterRegistry;

@Configuration
public class ArmeriaClientConfig {

    @Bean
    public ClientFactory clientFactory(PrometheusMeterRegistry registry) {
        // Save Armeria client metrics into the same registry with Armeria server
        return ClientFactory.builder()
                            .meterRegistry(registry)
                            .build();
    }
}
