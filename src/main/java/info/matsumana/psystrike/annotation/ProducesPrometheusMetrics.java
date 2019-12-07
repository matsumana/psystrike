package info.matsumana.psystrike.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.server.annotation.Produces;

@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
@Produces("text/plain; version=0.0.4")
public @interface ProducesPrometheusMetrics {
}
