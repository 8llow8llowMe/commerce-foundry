package com.commercefoundry.domainlayer.order.adapter.out.client;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.ShippingFeeClient;
import com.commercefoundry.domainlayer.order.adapter.out.client.feign.dto.ShippingFeeQuoteInternalRequest;
import com.commercefoundry.domainlayer.order.adapter.out.client.support.ShippingFeeInternalResponseSupport;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.port.out.ShippingFeeQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.ShippingScope;
import com.commercefoundry.shared.commerce.Money;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShippingFeeClientAdapter implements ShippingFeeQueryPort {

    /**
     * 배송 가능 국가 확인용 견적에 싣는 총 무게(그램). delivery 는 해외 견적의 본문 검증(무게 필수, {@code DELIVERY_118})을 해외 배송 플래그 · 정책 확인보다
     * 먼저 하므로 무게 없이 국가만 물을 수 없다 ({@code order-flow-guide.md} §2 "검증 순서"). 1g 이상이면 되므로 가장 작은 값을 싣는다.
     */
    static final int DESTINATION_CHECK_WEIGHT_GRAMS = 1;

    /** 확인용 견적의 거절 중 "국가 자체는 배송할 수 있고 다른 사유" 인 것 — 정책 미설정 · 통화 불일치 · 무게 구간 초과. */
    private static final Set<OrderErrorCode> DESTINATION_SUPPORTED_REJECTIONS = EnumSet.of(
        OrderErrorCode.SHIPPING_UNAVAILABLE, OrderErrorCode.DESTINATION_CURRENCY_NOT_SUPPORTED, OrderErrorCode.SHIPPING_WEIGHT_NOT_SUPPORTED);

    private final ShippingFeeClient shippingFeeClient;
    private final ShippingFeeInternalResponseSupport shippingFeeInternalResponseSupport;

    /** 조건을 그대로 옮긴다 — 어느 칸을 실을지(국내면 무게 null, 해외면 우편번호 선택)는 부른 Processor 가 정했다. */
    @Override
    public ShippingFeeQuoteQueryResult quote(ShippingFeeQuoteQuery query) {
        ShippingFeeQuoteInternalRequest request = toRequest(query);
        return shippingFeeInternalResponseSupport.toShippingFeeQuoteResult(
            shippingFeeInternalResponseSupport.call(() -> shippingFeeClient.quote(request)), query.orderAmount().currency(), query.countryCode());
    }

    /**
     * 같은 견적 엔드포인트에 총 무게 {@value #DESTINATION_CHECK_WEIGHT_GRAMS}g 으로 한 번 묻는다 — delivery 에 배송 가능 국가만 묻는 internal API 가 없다(#13 설계 (가)).
     * 응답의 해석은 여기에만 둔다(Processor 는 delivery 의 검증 순서 · 1g 사실을 모른다).
     *
     * <ul>
     *   <li>200 — 견적이 나왔다. 응답은 실제 견적과 같은 계약 검사를 거치고 버린다</li>
     *   <li>{@code ORDER_013}({@code DELIVERY_002}) — 배송할 수 없는 국가, false</li>
     *   <li>{@code ORDER_012}({@code DELIVERY_001}) · {@code ORDER_014}({@code 003}) · {@code ORDER_015}({@code 011}) — 국가는 배송할 수 있고 다른 사유다, true</li>
     *   <li>그 밖({@code ORDER_011} 503) — 그대로 던진다. 배송 가능 여부를 추측하지 않는다</li>
     * </ul>
     */
    @Override
    public boolean isDestinationSupported(Money orderAmount, String countryCode, String postalCode) {
        if (ShippingScope.ofCountry(countryCode) == ShippingScope.DOMESTIC) {
            throw new IllegalArgumentException("국내 배송 국가는 배송 가능 국가를 확인하지 않습니다");
        }
        try {
            quote(new ShippingFeeQuoteQuery(orderAmount, countryCode, postalCode, DESTINATION_CHECK_WEIGHT_GRAMS));
            return true;
        } catch (OrderException exception) {
            if (exception.getErrorCode() == OrderErrorCode.DESTINATION_NOT_SUPPORTED) {
                return false;
            }
            if (DESTINATION_SUPPORTED_REJECTIONS.contains(exception.getErrorCode())) {
                return true;
            }
            throw exception;
        }
    }

    private static ShippingFeeQuoteInternalRequest toRequest(ShippingFeeQuoteQuery query) {
        Money orderAmount = query.orderAmount();
        return new ShippingFeeQuoteInternalRequest(
            MoneyItem.of(orderAmount.amount(), orderAmount.currency().name()), query.countryCode(), query.postalCode(), query.weightGrams());
    }
}
