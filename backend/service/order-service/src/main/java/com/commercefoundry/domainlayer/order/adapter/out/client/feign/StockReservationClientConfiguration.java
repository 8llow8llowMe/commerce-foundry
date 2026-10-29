package com.commercefoundry.domainlayer.order.adapter.out.client.feign;

import feign.Logger;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

/** Client-local configuration; no @Configuration to avoid component scanning. */
public class StockReservationClientConfiguration {

    /**
     * Never retry an uncertain reservation. Client.Default uses fixed-length streaming for this POST,
     * preventing HttpURLConnection from replaying its body. Recheck transport retries if the HTTP client changes.
     */
    @Bean
    public Retryer stockReservationRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /** Do not log service tokens or response bodies. */
    @Bean
    public Logger.Level stockReservationLoggerLevel() {
        return Logger.Level.NONE;
    }
}
