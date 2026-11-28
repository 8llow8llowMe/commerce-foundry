package com.commercefoundry.global.config;

import java.util.function.LongSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 품절 임박 정기 점검(#229)의 경과 시간 단조 시계. 저장 Processor 와 PR-3b Facade 가 같은 빈을 쓴다. */
@Configuration(proxyBeanMethods = false)
public class LowStockAlertTickerConfig {
    @Bean(name = "lowStockAlertTicker", defaultCandidate = false)
    LongSupplier lowStockAlertTicker() {
        return System::nanoTime;
    }
}
