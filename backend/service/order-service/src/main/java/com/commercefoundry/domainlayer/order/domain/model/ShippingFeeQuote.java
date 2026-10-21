package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 주문서의 배송비 견적 — delivery-service 견적 조회 결과를 Processor 가 바꾼 domain 값이다(조회 결과를 Info · 도메인이 그대로 들지 않는다).
 * 주문 생성은 이 값을 주문의 배송비 스냅숏으로 저장하고, 클레임의 배송비 재부과는 현재 정책이 아니라 이 스냅숏으로 계산한다 ({@code order-flow-guide.md} §2).
 *
 * <p>금액은 모두 한 통화다. 총 무게는 해외 견적에만 있다 — 견적 요청에 실은 값(Σ SKU 무게 × 수량)이다 ({@code order-flow-guide.md} §2-2).
 *
 * <p>받을 배송비는 {@link #expectedShippingFee} 산식대로이고, 무료배송이면 무료배송 기준이 있다. 원격 응답의 같은 검사(주문 금액 대비 무료 판정 포함)는
 * adapter({@code ShippingFeeInternalResponseSupport})가 먼저 503 으로 거절한다 — 여기는 조립 버그를 막는 마지막 선이다.
 *
 * @param shippingFee 받을 배송비 — 주문 총액에 더한다
 * @param baseFee 정책 기본 배송비 (해외는 기본료)
 * @param freeShippingApplied 주문 금액이 무료배송 기준 이상이라 기본 배송비(해외는 기본료 + 무게 요금)를 받지 않았는가
 * @param remoteAreaSurcharge 도서산간 추가 배송비 — 무료배송이어도 받는다. 아니거나 해외면 0
 * @param remoteAreaName 도서산간 구간 이름. 아니거나 해외면 null
 * @param freeShippingThreshold 견적에 쓴 정책의 무료배송 기준. 없으면 null
 * @param shippingPolicyVersion 견적에 쓴 배송비 정책 version
 * @param scope 견적에 쓴 정책의 배송 범위
 * @param shippingZoneName 해외 국가 그룹 이름. 국내면 null
 * @param weightFee 해외 무게 요금. 국내면 null
 * @param totalWeightGrams 견적에 실은 총 무게(그램, 1 이상). 국내면 null
 */
public record ShippingFeeQuote(
    Money shippingFee, Money baseFee, boolean freeShippingApplied, Money remoteAreaSurcharge, String remoteAreaName,
    Money freeShippingThreshold, long shippingPolicyVersion, ShippingScope scope, String shippingZoneName, Money weightFee,
    Integer totalWeightGrams
) {

    public ShippingFeeQuote {
        Objects.requireNonNull(shippingFee, "shippingFee");
        Objects.requireNonNull(baseFee, "baseFee");
        Objects.requireNonNull(remoteAreaSurcharge, "remoteAreaSurcharge");
        Objects.requireNonNull(scope, "scope");
        CurrencyCode currency = shippingFee.currency();
        if (Stream.of(baseFee, remoteAreaSurcharge, freeShippingThreshold, weightFee).filter(Objects::nonNull).anyMatch(money -> money.currency() != currency)) {
            throw new IllegalArgumentException("배송비 견적의 금액 통화가 섞여 있습니다");
        }
        boolean weightConsistent = scope == ShippingScope.DOMESTIC ? totalWeightGrams == null : totalWeightGrams != null && totalWeightGrams >= 1;
        if (!weightConsistent) {
            throw new IllegalArgumentException("총 무게는 해외 견적에만 있고 1 이상입니다: scope=%s, totalWeightGrams=%s".formatted(scope, totalWeightGrams));
        }
        if (freeShippingApplied && freeShippingThreshold == null) {
            throw new IllegalArgumentException("무료배송 기준 없이 무료배송이 적용됐습니다");
        }
        Money expected = expectedShippingFee(scope, baseFee, freeShippingApplied, remoteAreaSurcharge, weightFee);
        if (!shippingFee.equals(expected)) {
            throw new IllegalArgumentException("배송비가 산식과 다릅니다: scope=%s, shippingFee=%s, expected=%s".formatted(scope, shippingFee, expected));
        }
    }

    /**
     * 받을 배송비의 산식 — delivery-service {@code ShippingPolicy#quote}(국내) · {@code InternationalShippingPolicy#quote}(해외)와 같다.
     *
     * <ul>
     *   <li>국내 — (무료배송이면 0, 아니면 기본 배송비) + 도서산간 추가 배송비. 도서산간 추가 배송비는 무료배송이어도 받는다</li>
     *   <li>해외 — 무료배송이면 0, 아니면 기본료 + 무게 요금</li>
     * </ul>
     *
     * @throws IllegalArgumentException 해외인데 무게 요금이 없거나, 금액 통화가 섞였다
     */
    public static Money expectedShippingFee(ShippingScope scope, Money baseFee, boolean freeShippingApplied, Money remoteAreaSurcharge, Money weightFee) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(baseFee, "baseFee");
        Money zero = Money.zero(baseFee.currency());
        return switch (scope) {
            case DOMESTIC -> (freeShippingApplied ? zero : baseFee).plus(Objects.requireNonNull(remoteAreaSurcharge, "remoteAreaSurcharge"));
            case INTERNATIONAL -> {
                if (weightFee == null) {
                    throw new IllegalArgumentException("해외 견적에는 무게 요금이 있어야 합니다");
                }
                yield freeShippingApplied ? zero : baseFee.plus(weightFee);
            }
        };
    }
}
