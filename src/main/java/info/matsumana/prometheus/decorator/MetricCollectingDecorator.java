package info.matsumana.prometheus.decorator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.server.annotation.DecoratorFactory;

@DecoratorFactory(MetricCollectingDecoratorFactoryFunction.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface MetricCollectingDecorator {

    String value() default "armeria.server";
}
