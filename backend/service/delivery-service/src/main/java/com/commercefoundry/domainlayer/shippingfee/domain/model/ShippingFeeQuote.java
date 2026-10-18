package com.commercefoundry.domainlayer.shippingfee.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 배송비 계산 결과. {@link ShippingPolicy#quote} 만 만든다.
 *
 * <p>{@code freeShippingThreshold} · {@code shippingPolicyVersion} 은 주문 시점 스냅숏용이다 — order-service 가 주문에 저장해 두고,
 * 부분 클레임으로 무료배송 기준 아래로 내려갈 때 배송비 재부과를 "현재 정책" 이 아니라 이 값으로 계산한다 ({@code order-flow-guide.md} §2).
 *
 * @param baseFee 정책 기본 배송비. 무료배송이면 {@code totalFee} 에 들어가지 않는다
 * @param freeShippingThreshold 정책 무료배송 기준. 없으면 null
 * @param freeShippingApplied 주문 금액이 무료배송 기준 이상이라 기본 배송비를 받지 않았는가
 * @param remoteAreaSurcharge 도서산간 추가 배송비. 도서산간이 아니면 0
 * @param remoteAreaName 우편번호가 속한 도서산간 구간 이름. 아니면 null
 * @param totalFee 받을 배송비 = (무료배송이면 0, 아니면 기본 배송비) + 도서산간 추가 배송비
 * @param shippingPolicyVersion 견적에 쓴 정책 버전
 */
public record ShippingFeeQuote(
    Money baseFee, Money freeShippingThreshold, boolean freeShippingApplied,
    Money remoteAreaSurcharge, String remoteAreaName, Money totalFee, long shippingPolicyVersion
) {

    public ShippingFeeQuote {
        Objects.requireNonNull(baseFee, "baseFee");
        Objects.requireNonNull(remoteAreaSurcharge, "remoteAreaSurcharge");
        Objects.requireNonNull(totalFee, "totalFee");
    }
}
