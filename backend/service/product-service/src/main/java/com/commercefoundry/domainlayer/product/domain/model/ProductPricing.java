package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 상품 가격 계산의 유일한 진입점이다. 목록 · 상세 · 서비스 간 SKU 조회가 모두 여기를 거친다 —
 * 화면과 주문이 같은 SKU 에 다른 판매가를 보면 주문 금액 검증이 어긋난다.
 *
 * <p>금액 연산 자체(덧셈 · 통화 검사 · 반올림)는 {@link Money} 에 맡긴다.
 */
public final class ProductPricing {

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private ProductPricing() {
    }

    /**
     * SKU 판매가 = 상품 기본 판매가 + 옵션 추가금.
     *
     * @throws IllegalArgumentException 통화가 다를 때 ({@link Money#plus})
     */
    public static Money skuSalePrice(Money base, Money additional) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(additional, "additional");
        return base.plus(additional);
    }

    /**
     * 정가 대비 할인율(%) — {@code (정가 − 판매가) × 100 / 정가} 를 정수로 버린다. 표시용이라 할인율을 부풀리지 않는다.
     *
     * @param list 정가. null 이면 할인 표시가 없다
     * @return 정가가 없거나, 판매가 이하이거나, 통화가 다르면 null
     */
    public static Integer discountRate(Money list, Money sale) {
        Objects.requireNonNull(sale, "sale");
        if (list == null || list.currency() != sale.currency() || list.amount() <= sale.amount()) {
            return null;
        }

        BigDecimal discount = BigDecimal.valueOf(list.amount() - sale.amount());
        return discount.multiply(PERCENT)
            .divide(BigDecimal.valueOf(list.amount()), 0, RoundingMode.DOWN)
            .intValueExact();
    }
}
