package info.matsumana.psystrike.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.Value;

@Component
public class AppVersionHelper {

    private static final String PROP_RESOURCE_PATH = "META-INF/info.matsumana.psystrike.versions.properties";
    private static final String PROP_VERSION = ".version";
    private static final String PROP_LONG_COMMIT_HASH = ".longCommitHash";
    private static final String PROP_REPO_STATUS = ".repoStatus";

    @Value
    public static class Version {
        String artifactVersion;
        String longCommitHash;
        String repositoryStatus;
    }

    public Version getVersion() {
        final Map<String, Version> map;
        map = generateVersionMap();
        return map.get("psystrike");
    }

    private Map<String, Version> generateVersionMap() {
        final Properties props = new Properties();

        try {
            final Enumeration<URL> resources = getClass().getClassLoader().getResources(PROP_RESOURCE_PATH);

            // Collect all properties.
            while (resources.hasMoreElements()) {
                final URL url = resources.nextElement();
                try (InputStream in = url.openStream()) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
