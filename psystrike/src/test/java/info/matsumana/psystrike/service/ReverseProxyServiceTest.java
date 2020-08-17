package info.matsumana.psystrike.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.linecorp.armeria.client.WebClient;

import info.matsumana.psystrike.config.CleanupTimerProperties;

@SpringBootTest
public class ReverseProxyServiceTest {

    @Autowired
    Clock clock;

    @Autowired
    ReverseProxyService reverseProxyService;

    @Test
    void generatePrefix() {
        assertThat(ReverseProxyService.generatePrefix("/foo/")).isEqualTo("/foo");
        assertThat(ReverseProxyService.generatePrefix("/foo")).isEqualTo("/foo");
        assertThat(ReverseProxyService.generatePrefix("foo/")).isEqualTo("/foo");
        assertThat(ReverseProxyService.generatePrefix("foo")).isEqualTo("/foo");

        assertThat(ReverseProxyService.generatePrefix("/foo/bar/")).isEqualTo("/foo/bar");
        assertThat(ReverseProxyService.generatePrefix("/foo/bar")).isEqualTo("/foo/bar");
        assertThat(ReverseProxyService.generatePrefix("foo/bar/")).isEqualTo("/foo/bar");
        assertThat(ReverseProxyService.generatePrefix("foo/bar")).isEqualTo("/foo/bar");

        assertThat(ReverseProxyService.generatePrefix("/")).isEmpty();
        assertThat(ReverseProxyService.generatePrefix("")).isEmpty();
    }

    @Test
    void generateRequestHeaderUserAgent() {
        final String userAgent = reverseProxyService.generateRequestHeaderUserAgent();
        assertThat(userAgent).startsWith("psystrike/");
    }

    @Test
    void setupWebClientsCleanupTimer() throws InterruptedException {
        final int SLEEP_BUFFER_MILLIS = 1_000;

        final CleanupTimerProperties cleanupTimerProperties = new CleanupTimerProperties();
        cleanupTimerProperties.setDelaySeconds(1);
        cleanupTimerProperties.setPeriodSeconds(10);
        cleanupTimerProperties.setRemoveThresholdSeconds(30);
        final LocalDateTime now = LocalDateTime.now(clock);
        final Map<String, Pair<WebClient, LocalDateTime>> webClients = new ConcurrentHashMap<>();
        webClients.put("host1:8080", ImmutablePair.of(WebClient.of(), now.minusSeconds(10)));
        webClients.put("host2:8080", ImmutablePair.of(WebClient.of(), now.minusSeconds(20)));
        webClients.put("host3:8080", ImmutablePair.of(WebClient.of(), now.minusSeconds(30)));

        reverseProxyService.setupWebClientsCleanupTimer(webClients, clock, cleanupTimerProperties);
        assertThat(webClients.size()).isEqualTo(3);

        Thread.sleep(cleanupTimerProperties.getDelaySeconds() * 1_000 + SLEEP_BUFFER_MILLIS);
        assertThat(webClients.size()).isEqualTo(2);
        assertThat(webClients.get("host1:8080")).isNotNull();
        assertThat(webClients.get("host2:8080")).isNotNull();

        Thread.sleep(cleanupTimerProperties.getPeriodSeconds() * 1_000 + SLEEP_BUFFER_MILLIS);
        assertThat(webClients.size()).isEqualTo(1);
        assertThat(webClients.get("host1:8080")).isNotNull();

        Thread.sleep(cleanupTimerProperties.getPeriodSeconds() * 1_000 + SLEEP_BUFFER_MILLIS);
        assertThat(webClients.size()).isEqualTo(0);
    }
}
