package info.matsumana.kubernetes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "info-matsumana")
@Data
public class KubernetesProperties {
    private String kubernetesApiServer = "kubernetes.default.svc.cluster.local";
    private String kubernetesToken;
}
