package info.matsumana.psystrike.metrics;

import java.util.List;

import org.springframework.context.annotation.Configuration;

import info.matsumana.psystrike.helper.AppVersionHelper;
import info.matsumana.psystrike.helper.AppVersionHelper.Version;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

@Configuration
public class AppVersionMetricsConfig {

    private final AppVersionHelper appVersionHelper;

    public AppVersionMetricsConfig(MeterRegistry meterRegistry, AppVersionHelper appVersionHelper) {
        this.appVersionHelper = appVersionHelper;

        exportMetrics(meterRegistry);
    }

    private void exportMetrics(MeterRegistry meterRegistry) {
        final Version versionInfo = appVersionHelper.getVersion();
        final String version = versionInfo.getArtifactVersion();
        final String commit = versionInfo.getLongCommitHash();
        final String repositoryStatus = versionInfo.getRepositoryStatus();
        final List<Tag> tags = List.of(Tag.of("version", version),
                                       Tag.of("commit", commit),
                                       Tag.of("repo_status", repositoryStatus));
        Gauge.builder("psystrike.build.info", () -> 1)
             .tags(tags)
             .description("A metric with a constant '1' value labeled by version and commit hash" +
                          " from which Psystrike was built.")
             .register(meterRegistry);
    }

}
