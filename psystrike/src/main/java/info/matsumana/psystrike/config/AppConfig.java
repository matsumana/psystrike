package info.matsumana.psystrike.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        // Set clock tick to 1 millisecond
        return Clock.tick(Clock.systemDefaultZone(), Duration.ofMillis(1));
    }
}
