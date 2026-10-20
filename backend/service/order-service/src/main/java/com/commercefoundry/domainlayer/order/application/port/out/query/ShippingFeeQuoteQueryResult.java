package com.commercefoundry.domainlayer.order.application.port.out.query;

import com.commercefoundry.domainlayer.order.domain.model.ShippingScope;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * delivery-service 배송비 견적 한 건. 원격 응답 모양(Feign DTO)은 adapter 밖으로 나오지 않고 이 값으로만 나온다.
 *
 * <p>주문 생성은 {@code shippingFee} 를 총액에 더하고 나머지(기본 배송비 · 무료배송 기준 · 도서산간 · 정책 version, 해외는 국가 그룹 · 무게 요금)를 주문에 스냅숏한다 —
 * 클레임 때 배송비 재부과를 현재 정책이 아니라 이 값으로 계산한다 ({@code order-flow-guide.md} §2).
 *
 * <p>금액은 모두 한 통화다. 섞여 있으면 계약 위반이라 만들지 않는다(adapter 가 503 으로 바꾼다).
 *
 * @param shippingFee 받을 배송비 — 국내 (무료배송이면 0, 아니면 baseFee) + 도서산간 추가 배송비, 해외 무료배송이면 0, 아니면 baseFee + weightFee
 * @param baseFee 정책 기본 배송비 (해외는 모든 해외 배송에 붙는 기본료). 무료배송이면 shippingFee 에 들어가지 않는다
 * @param freeShippingApplied 주문 금액이 무료배송 기준 이상이라 기본 배송비(해외는 기본료 + 무게 요금)를 받지 않았는가
 * @param remoteAreaSurcharge 도서산간 추가 배송비 — 무료배송이어도 받는다. 도서산간이 아니거나 해외면 0
 * @param remoteAreaName 우편번호가 속한 도서산간 구간 이름. 아니거나 해외면 null
 * @param freeShippingThreshold 견적에 쓴 정책의 무료배송 기준. 없으면 null
 * @param shippingPolicyVersion 견적에 쓴 배송비 정책 version
 * @param scope 견적에 쓴 정책의 배송 범위
 * @param shippingZoneName 배송 국가가 속한 해외 국가 그룹 이름. 국내면 null
 * @param weightFee 해외 무게 요금. 국내면 null
 */
public record ShippingFeeQuoteQueryResult(
    Money shippingFee, Money baseFee, boolean freeShippingApplied, Money remoteAreaSurcharge, String remoteAreaName,
    Money freeShippingThreshold, long shippingPolicyVersion, ShippingScope scope, String shippingZoneName, Money weightFee
) {

    public ShippingFeeQuoteQueryResult {
        Objects.requireNonNull(shippingFee, "shippingFee");
        Objects.requireNonNull(baseFee, "baseFee");
        Objects.requireNonNull(remoteAreaSurcharge, "remoteAreaSurcharge");
        Objects.requireNonNull(scope, "scope");
        CurrencyCode currency = shippingFee.currency();
        if (Stream.of(baseFee, remoteAreaSurcharge, freeShippingThreshold, weightFee).filter(Objects::nonNull).anyMatch(money -> money.currency() != currency)) {
            throw new IllegalArgumentException("배송비 견적의 금액 통화가 섞여 있습니다");
        }
    }
}
