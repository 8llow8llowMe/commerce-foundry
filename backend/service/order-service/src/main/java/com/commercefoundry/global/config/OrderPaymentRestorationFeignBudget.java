package com.commercefoundry.global.config;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.cloud.openfeign.FeignClientProperties.FeignClientConfiguration;

/** 복원 배치 예산을 계산할 때 사용하는 두 Feign 클라이언트의 설정 경계. */
final class OrderPaymentRestorationFeignBudget {

    private OrderPaymentRestorationFeignBudget() {
    }

    static void requireSupported(FeignClientProperties properties) {
        Map<String, FeignClientConfiguration> configs = properties.getConfig();
        FeignClientConfiguration defaults = configs.get(properties.getDefaultConfig());
        requireSupported(defaults, configs.get("paymentClient"), "paymentClient");
        requireSupported(defaults, configs.get("stockRestorationClient"), "stockRestorationClient");
    }

    private static void requireSupported(FeignClientConfiguration defaults, FeignClientConfiguration client, String contextId) {
        int connect = timeout(client == null ? null : client.getConnectTimeout(), defaults == null ? null : defaults.getConnectTimeout());
        int read = timeout(client == null ? null : client.getReadTimeout(), defaults == null ? null : defaults.getReadTimeout());
        boolean retryOverride = (defaults != null && defaults.getRetryer() != null) || (client != null && client.getRetryer() != null);
        if (connect < 1 || connect > 2_000 || read < 1 || read > 3_000 || retryOverride) {
            throw new IllegalStateException("order.payment-restoration.sweep.enabled requires connect <= 2000ms, read <= 3000ms and no Feign retry "
                + "override: " + contextId);
        }
    }

    private static int timeout(Integer clientValue, Integer defaultValue) {
        return clientValue != null ? clientValue : defaultValue == null ? 0 : defaultValue;
    }
}
