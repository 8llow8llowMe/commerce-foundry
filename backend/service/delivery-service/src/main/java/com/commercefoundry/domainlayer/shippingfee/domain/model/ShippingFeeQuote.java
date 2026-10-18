package com.commercefoundry.domainlayer.shippingfee.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 배송비 계산 결과. {@link ShippingPolicy#quote}(국내) · {@link InternationalShippingPolicy#quote}(해외)만 만든다.
 *
 * <p>{@code freeShippingThreshold} · {@code shippingPolicyVersion} 은 주문 시점 스냅숏용이다 — order-service 가 주문에 저장해 두고,
 * 부분 클레임으로 무료배송 기준 아래로 내려갈 때 배송비 재부과를 "현재 정책" 이 아니라 이 값으로 계산한다 ({@code order-flow-guide.md} §2).
 *
 * <p>범위마다 채우는 항목이 다르다 — 국내는 도서산간({@code remoteArea*}), 해외는 국가 그룹 · 무게 요금({@code shippingZoneName} · {@code weightFee}).
 * 상대 범위의 항목은 null(이름 · 무게 요금) 또는 0(도서산간 추가 배송비)이다.
 *
 * @param scope 견적에 쓴 정책의 배송 범위
 * @param baseFee 정책 기본 배송비 (해외는 모든 해외 배송에 붙는 기본료). 무료배송이면 {@code totalFee} 에 들어가지 않는다
 * @param freeShippingThreshold 정책 무료배송 기준. 없으면 null
 * @param freeShippingApplied 주문 금액이 무료배송 기준 이상이라 기본 배송비(해외는 기본료 + 무게 요금)를 받지 않았는가
 * @param remoteAreaSurcharge 도서산간 추가 배송비. 도서산간이 아니거나 해외면 0
 * @param remoteAreaName 우편번호가 속한 도서산간 구간 이름. 아니거나 해외면 null
 * @param shippingZoneName 배송 국가가 속한 해외 국가 그룹 이름. 국내면 null
 * @param weightFee 해외 무게 요금(무료배송이어도 원래 요금을 낸다). 국내면 null
 * @param totalFee 받을 배송비 — 국내 (무료배송이면 0, 아니면 기본 배송비) + 도서산간 추가 배송비, 해외 무료배송이면 0, 아니면 기본료 + 무게 요금
 * @param shippingPolicyVersion 견적에 쓴 정책 버전
 */
public record ShippingFeeQuote(
    ShippingScope scope, Money baseFee, Money freeShippingThreshold, boolean freeShippingApplied,
    Money remoteAreaSurcharge, String remoteAreaName, String shippingZoneName, Money weightFee,
    Money totalFee, long shippingPolicyVersion
) {

    public ShippingFeeQuote {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(baseFee, "baseFee");
        Objects.requireNonNull(remoteAreaSurcharge, "remoteAreaSurcharge");
        Objects.requireNonNull(totalFee, "totalFee");
        if (scope == ShippingScope.DOMESTIC && (shippingZoneName != null || weightFee != null)) {
            throw new IllegalArgumentException("국내 견적에는 국가 그룹 · 무게 요금이 없습니다");
        }
        if (scope == ShippingScope.INTERNATIONAL) {
            Objects.requireNonNull(shippingZoneName, "shippingZoneName");
            Objects.requireNonNull(weightFee, "weightFee");
            if (remoteAreaName != null || !remoteAreaSurcharge.isZero()) {
                throw new IllegalArgumentException("해외 견적에는 도서산간 추가 배송비가 없습니다");
            }
        }
    }
}
