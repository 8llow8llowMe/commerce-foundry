package com.commercefoundry.global.config;

import java.util.function.LongSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PaymentAnomalyTickerConfig {
    @Bean(name = "paymentAnomalyTicker", defaultCandidate = false)
    LongSupplier paymentAnomalyTicker() {
        return System::nanoTime;
    }
}
