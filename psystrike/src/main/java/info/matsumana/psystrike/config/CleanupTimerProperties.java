package info.matsumana.psystrike.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "psystrike-cleanup-timer")
@Data
public class CleanupTimerProperties {
    private int delaySeconds;
    private int periodSeconds;
    private int removeThresholdSeconds;
}
