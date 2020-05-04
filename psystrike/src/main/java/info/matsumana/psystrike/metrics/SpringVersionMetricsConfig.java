package info.matsumana.psystrike.metrics;

import java.util.List;

import org.springframework.boot.SpringBootVersion;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

@Configuration
public class SpringVersionMetricsConfig {

    public SpringVersionMetricsConfig(MeterRegistry meterRegistry) {
        final String version = SpringBootVersion.getVersion();
        final List<Tag> tags = List.of(Tag.of("version", version));
        Gauge.builder("spring.boot.build.info", () -> 1)
             .tags(tags)
             .description("A metric with a constant '1' value labeled by version"
                          + " from which Spring Boot is used by the app.")
             .register(meterRegistry);
    }
}
