package info.matsumana.kubernetes.decorator;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.metric.MetricCollectingService;

public class MetricCollectingDecoratorFactoryFunction
        implements DecoratorFactoryFunction<MetricCollectingDecorator> {

    @Override
    public Function<Service<HttpRequest, HttpResponse>, ? extends Service<HttpRequest, HttpResponse>> newDecorator(
            MetricCollectingDecorator parameter) {
        return MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault(parameter.value()));
    }
}
