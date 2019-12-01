package info.matsumana.kubernetes.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class ReverseProxyServiceTest {

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
}
