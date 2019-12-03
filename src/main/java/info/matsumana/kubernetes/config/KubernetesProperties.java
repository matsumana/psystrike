package info.matsumana.kubernetes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "psystrike-kubernetes")
@Data
public class KubernetesProperties {
    private String apiServer = "kubernetes.default.svc.cluster.local";
    private int apiServerPort = 443;
    private String apiUriPrefix = "";
    private String bearerToken;
}
