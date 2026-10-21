package com.commercefoundry.domainlayer.order.application.model;

import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.Objects;

/**
 * 배송비 견적 조건 ({@code ShippingFeeQueryPort#quote}). 어느 칸을 실을지는 부른 Processor 가 정하고 adapter 는 그대로 옮긴다 —
 * 국내(KR)면 우편번호(숫자 5자리)를 싣고 무게는 null, 해외면 총 무게(Σ SKU 무게 × 수량)를 싣고 우편번호는 있을 때만 싣는다 ({@code order-flow-guide.md} §2).
 *
 * <p>delivery 의 본문 규칙 (delivery-service {@code ShippingFeeQuoteInternalRequest} · {@code QuoteDestinationValidator}):
 * <ul>
 *   <li>{@code countryCode} — {@code ^[A-Z]{2}$}</li>
 *   <li>KR — {@code postalCode} 필수, {@code ^[0-9]{5}$}</li>
 *   <li>KR 외 — {@code postalCode} 선택, 있으면 {@code ^[A-Za-z0-9 -]{1,20}$}(비었거나 공백뿐이면 없는 값). {@code weightGrams} 필수, 1 이상</li>
 * </ul>
 * 위반은 delivery 의 {@code DELIVERY_1xx}(400)이고 우리 쪽에서는 503 {@code ORDER_011} 이 된다 — 고객 입력 오류가 장애처럼 보인다.
 * 그래서 <b>호출하는 쪽(주문 요청 검증)이 이 규칙보다 느슨하면 안 된다</b>.
 *
 * <p>생성자는 그중 필수 칸만 막는다 — 국가 코드가 대문자 두 글자가 아니거나, KR 인데 우편번호가 없거나(공백뿐 포함), KR 외인데 1 이상의 총 무게가 없으면
 * {@link IllegalArgumentException} 이다. 조립 버그가 delivery 400 → 503 으로 가려지지 않고 바로 드러나게. 우편번호 형식(정규식)은 주문 요청 검증이 본다.
 *
 * <p>{@link #toString()} 은 우편번호를 가린다 — 배송지의 일부라 로그에 남기지 않는다 ({@code coding-conventions.md} §8).
 *
 * @param orderAmount 할인 후 상품 합계(배송비 제외). delivery 는 무료배송 기준과 비교만 한다. 통화가 그 범위 정책의 통화와 다르면 거절된다(환산하지 않는다, #70)
 * @param countryCode 배송 국가 (ISO 3166-1 alpha-2 대문자)
 * @param postalCode 배송지 우편번호. KR 이면 필수, 그 밖의 국가는 선택(null)
 * @param weightGrams 배송할 상품 총 무게(그램, 1 이상). KR 외 국가면 필수, KR 이면 null
 */
public record ShippingFeeQuoteQuery(Money orderAmount, String countryCode, String postalCode, Integer weightGrams) {

    public ShippingFeeQuoteQuery {
        Objects.requireNonNull(orderAmount, "orderAmount");
        Objects.requireNonNull(countryCode, "countryCode");
        // 국가 형식은 주문 요청 검증이 먼저 거른다 — 여기까지 온 형식 오류는 조립 버그다
        ShippingScope scope = ShippingScope.ofCountry(countryCode)
            .orElseThrow(() -> new IllegalArgumentException("배송 국가 코드가 ISO 3166-1 alpha-2 대문자 형식이 아닙니다"));
        if (scope == ShippingScope.DOMESTIC) {
            if (postalCode == null || postalCode.isBlank()) {
                throw new IllegalArgumentException("국내(KR) 배송비 견적에는 우편번호가 있어야 합니다");
            }
        } else if (weightGrams == null || weightGrams < 1) {
            throw new IllegalArgumentException("해외 배송비 견적에는 1 이상의 총 무게가 있어야 합니다");
        }
    }

    @Override
    public String toString() {
        return "ShippingFeeQuoteQuery[orderAmount=%s, countryCode=%s, postalCode=%s, weightGrams=%s]"
            .formatted(orderAmount, countryCode, postalCode == null ? null : "***", weightGrams);
    }
}
