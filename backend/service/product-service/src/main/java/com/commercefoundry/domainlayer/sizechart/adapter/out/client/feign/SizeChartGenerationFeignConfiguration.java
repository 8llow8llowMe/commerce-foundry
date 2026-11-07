package com.commercefoundry.domainlayer.sizechart.adapter.out.client.feign;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;

/** Child context only: never enable body logging or retry a registration automatically. */
public class SizeChartGenerationFeignConfiguration {
    @Bean
    public Request.Options sizeChartGenerationOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 3, TimeUnit.SECONDS, false);
    }

    @Bean
    public Retryer sizeChartGenerationRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Logger.Level sizeChartGenerationLoggerLevel() {
        return Logger.Level.NONE;
    }
}
