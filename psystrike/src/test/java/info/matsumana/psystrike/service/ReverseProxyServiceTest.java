package info.matsumana.psystrike.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ReverseProxyServiceTest {

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
}
