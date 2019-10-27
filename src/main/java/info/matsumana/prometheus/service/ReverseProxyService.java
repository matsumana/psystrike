package info.matsumana.prometheus.service;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

import info.matsumana.prometheus.decorator.MetricCollectingDecorator;

@LoggingDecorator(requestLogLevel = LogLevel.INFO)
@MetricCollectingDecorator
public class ReverseProxyService {

    @Get("/proxy/:param")
    public HttpResponse greet(@Param String param) {
        return HttpResponse.of("param = %s", param);
    }
}
