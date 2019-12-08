package info.matsumana.psystrike.decorator;

import java.util.function.Function;

import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.annotation.DecoratorFactoryFunction;
import com.linecorp.armeria.server.metric.MetricCollectingService;

public class MetricCollectingDecoratorFactoryFunction
        implements DecoratorFactoryFunction<MetricCollectingDecorator> {

    @Override
    public Function<? super HttpService, MetricCollectingService> newDecorator(
            MetricCollectingDecorator parameter) {
        return MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault(parameter.value()));
    }
}
