package com.commercefoundry.domainlayer.notification.adapter.out.client;

import com.commercefoundry.domainlayer.notification.adapter.out.client.feign.LowStockObservationClient;
import com.commercefoundry.domainlayer.notification.adapter.out.client.support.LowStockObservationInternalResponseSupport;
import com.commercefoundry.domainlayer.notification.application.port.out.LowStockObservationPort;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LowStockObservationAdapter implements LowStockObservationPort {
    private final LowStockObservationClient client;
    private final LowStockObservationInternalResponseSupport support;

    @Override
    public LowStockAlertPolicy.Page observe(long afterSkuId, int size) {
        support.requireRequest(afterSkuId, size);
        return support.toPage(support.call(() -> client.lowStocks(afterSkuId, size)), afterSkuId, size);
    }
}
