package com.commercefoundry.domainlayer.order.adapter.out.client;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.ShippingFeeClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.ShippingFeeQuoteInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.ShippingFeeInternalResponseSupport;
import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.port.out.ShippingFeeQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.shared.commerce.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShippingFeeClientAdapter implements ShippingFeeQueryPort {

    private final ShippingFeeClient shippingFeeClient;
    private final ShippingFeeInternalResponseSupport shippingFeeInternalResponseSupport;

    /** 조건을 그대로 옮긴다 — 어느 칸을 실을지(국내면 무게 null, 해외면 우편번호 선택)는 부른 Processor 가 정했다. */
    @Override
    public ShippingFeeQuoteQueryResult quote(ShippingFeeQuoteQuery query) {
        ShippingFeeQuoteInternalRequest request = toRequest(query);
        return shippingFeeInternalResponseSupport.toShippingFeeQuoteResult(
            shippingFeeInternalResponseSupport.call(() -> shippingFeeClient.quote(request)), query.orderAmount().currency(), query.countryCode());
    }

    private static ShippingFeeQuoteInternalRequest toRequest(ShippingFeeQuoteQuery query) {
        Money orderAmount = query.orderAmount();
        return new ShippingFeeQuoteInternalRequest(
            MoneyItem.of(orderAmount.amount(), orderAmount.currency().name()), query.countryCode(), query.postalCode(), query.weightGrams());
    }
}
