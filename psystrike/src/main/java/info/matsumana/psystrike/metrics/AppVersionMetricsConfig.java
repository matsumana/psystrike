package info.matsumana.psystrike.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

@Configuration
public class AppVersionMetricsConfig {

    private static final String PROP_RESOURCE_PATH = "META-INF/info.matsumana.psystrike.versions.properties";
    private static final String PROP_VERSION = ".version";
    private static final String PROP_LONG_COMMIT_HASH = ".longCommitHash";
    private static final String PROP_REPO_STATUS = ".repoStatus";

    private static final class Version {
        private final String artifactVersion;
        private final String longCommitHash;
        private final String repositoryStatus;

        private Version(String artifactVersion, String longCommitHash, String repositoryStatus) {
            this.artifactVersion = artifactVersion;
            this.longCommitHash = longCommitHash;
            this.repositoryStatus = repositoryStatus;
        }
    }

    public AppVersionMetricsConfig(MeterRegistry meterRegistry) {
        exportMetrics(meterRegistry);
    }

    private void exportMetrics(MeterRegistry meterRegistry) {
        final Map<String, Version> map;
        try {
            map = generateVersionMap();
        } catch (IOException ignore) {
            return;
        }

        final Version versionInfo = map.get("psystrike");
        final String version = versionInfo.artifactVersion;
        final String commit = versionInfo.longCommitHash;
        final String repositoryStatus = versionInfo.repositoryStatus;
        final List<Tag> tags = List.of(Tag.of("version", version),
                                       Tag.of("commit", commit),
                                       Tag.of("repoStatus", repositoryStatus));
        Gauge.builder("psystrike.build.info", () -> 1)
             .tags(tags)
             .description("A metric with a constant '1' value labeled by version and commit hash" +
                          " from which Psystrike was built.")
             .register(meterRegistry);
    }

    private Map<String, Version> generateVersionMap() throws IOException {
        final Enumeration<URL> resources = getClass().getClassLoader().getResources(PROP_RESOURCE_PATH);

        // Collect all properties.
        final Properties props = new Properties();
        while (resources.hasMoreElements()) {
            final URL url = resources.nextElement();
            try (InputStream in = url.openStream()) {
                props.load(in);
            }
        }

        // Collect all artifactIds.
        final Set<String> artifactIds = new HashSet<>();
        for (Object o : props.keySet()) {
            final String k = (String) o;

            final int dotIndex = k.indexOf('.');
            if (dotIndex <= 0) {
                continue;
            }

            final String artifactId = k.substring(0, dotIndex);

            artifactIds.add(artifactId);
        }

        final Map<String, Version> versions = new HashMap<>();
        for (String artifactId : artifactIds) {
            versions.put(artifactId,
                         new Version(props.getProperty(artifactId + PROP_VERSION),
                                     props.getProperty(artifactId + PROP_LONG_COMMIT_HASH),
                                     props.getProperty(artifactId + PROP_REPO_STATUS)));
        }

        return Map.copyOf(versions);
    }
}
