package info.matsumana.kubernetes;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;

import java.security.cert.CertificateException;

import javax.net.ssl.SSLException;

import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;

import info.matsumana.kubernetes.service.ReverseProxyService;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Application {

    private static final int PORT = 8080;

    private static final PrometheusMeterRegistry registry = PrometheusMeterRegistries.newRegistry();
    private static final UptimeMetrics uptimeMetrics = new UptimeMetrics();
    private static final LogbackMetrics logbackMetrics = new LogbackMetrics();
    private static final JvmGcMetrics jvmGcMetrics = new JvmGcMetrics();
    private static final JvmMemoryMetrics jvmMemoryMetrics = new JvmMemoryMetrics();
    private static final JvmThreadMetrics jvmThreadMetrics = new JvmThreadMetrics();
    private static final ClassLoaderMetrics classLoaderMetrics = new ClassLoaderMetrics();

    public static void main(String[] args) throws Exception {
        configureMetrics();

        final Server server = newServer();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Start shutting down.");
            server.stop().join();
            log.info("Server has been stopped.");
        }));

        server.start().join();
    }

    private static void configureMetrics() {
        uptimeMetrics.bindTo(registry);
        logbackMetrics.bindTo(registry);
        jvmGcMetrics.bindTo(registry);
        jvmMemoryMetrics.bindTo(registry);
        jvmThreadMetrics.bindTo(registry);
        classLoaderMetrics.bindTo(registry);
    }

    private static Server newServer() throws SSLException, CertificateException {
        return Server.builder()
                     .port(PORT, HTTP, HTTPS)  // Can serve on the same port by Protocol auto detection
                     .tlsSelfSigned()
                     .meterRegistry(registry)
                     .serviceUnder("/healthz", HealthCheckService.builder().build())
                     .service("/metrics", new PrometheusExpositionService(registry.getPrometheusRegistry()))
                     .annotatedService(new ReverseProxyService(registry))
                     .build();
    }
}
