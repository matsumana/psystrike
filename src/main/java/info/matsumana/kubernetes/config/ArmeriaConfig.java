package info.matsumana.kubernetes.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.web.reactive.ArmeriaClientConfigurator;

import info.matsumana.kubernetes.service.ReverseProxyService;
import lombok.AllArgsConstructor;

@Configuration
@AllArgsConstructor
public class ArmeriaConfig {

    public static final int CLIENT_MAX_RESPONSE_LENGTH_BYTE = 100 * 1024 * 1024;

    // TODO make const and link to k8s source code
    private static final int RESPONSE_TIMEOUT_MIN = 10;
    private static final int WRITE_TIMEOUT_MIN = 10;

    @Bean
    public ArmeriaServerConfigurator armeriaServerConfigurator(ReverseProxyService reverseProxyService) {
        return builder -> builder.annotatedService(reverseProxyService);
    }

    @Bean
    public ArmeriaClientConfigurator armeriaClientConfigurator() {
        return builder -> builder.maxResponseLength(CLIENT_MAX_RESPONSE_LENGTH_BYTE)
                                 .responseTimeout(Duration.ofMinutes(RESPONSE_TIMEOUT_MIN))
                                 .writeTimeout(Duration.ofMinutes(WRITE_TIMEOUT_MIN));
    }
}
