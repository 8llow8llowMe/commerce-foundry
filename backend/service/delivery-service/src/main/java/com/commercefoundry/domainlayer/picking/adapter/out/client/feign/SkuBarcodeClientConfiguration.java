package com.commercefoundry.domainlayer.picking.adapter.out.client.feign;

import feign.Logger;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

public class SkuBarcodeClientConfiguration {
    @Bean
    public Retryer skuBarcodeRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Logger.Level skuBarcodeLoggerLevel() {
        return Logger.Level.NONE;
    }
}
