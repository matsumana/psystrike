package info.matsumana.psystrike.metrics;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

@Configuration
public class JvmVersionMetricsConfig {

    public JvmVersionMetricsConfig(@Value("${java.vm.vendor}") String vmVendor,
                                   @Value("${java.vm.version}") String vmVersion,
                                   @Value("${java.version}") String version,
                                   MeterRegistry meterRegistry) {
        final List<Tag> tags = List.of(Tag.of("vm.vendor", vmVendor),
                                       Tag.of("vm.version", vmVersion),
                                       Tag.of("version", version));
        Gauge.builder("jvm.build.info", () -> 1)
             .tags(tags)
             .description("A metric with a constant '1' value labeled by version"
                          + " from which JVM is used by the app.")
             .register(meterRegistry);
    }
}
