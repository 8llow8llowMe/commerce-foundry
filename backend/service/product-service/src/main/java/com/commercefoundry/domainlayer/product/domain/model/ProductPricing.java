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
     * 상품 단위 표시 판매가 (목록 항목 · 상세 상단) = 기본 판매가 + 판매중 SKU 추가금의 최소값. 판매중 SKU 가 없으면 기본 판매가 그대로다.
     *
     * <p>상품 등록(#9)이 "기본 판매가 = 판매중 SKU 최저가" 불변식을 지키면 최소 추가금은 0 이라 기본 판매가와 같다.
     * 불변식이 깨진 데이터에서도 화면에 살 수 없는 가격(판매중 SKU 어디에도 없는 가격)을 보이지 않게 최소 추가금을 더한다.
     *
     * @param minOnSaleAdditionalAmount 판매중 SKU 추가금의 최소값(최소 화폐 단위). 통화는 기본 판매가를 따른다 ({@link Sku} 불변식). 판매중 SKU 가 없으면 null
     */
    public static Money displaySalePrice(Money base, Long minOnSaleAdditionalAmount) {
        Objects.requireNonNull(base, "base");
        if (minOnSaleAdditionalAmount == null) {
            return base;
        }
        return base.plus(Money.of(minOnSaleAdditionalAmount, base.currency()));
    }

    /**
     * 정가 대비 할인율(%) — {@code (정가 − 판매가) × 100 / 정가} 를 정수로 버린다. 표시용이라 할인율을 부풀리지 않는다.
     *
     * <p>할인이 없으면 null 이다 (FE 계약). 버려서 0 이 되는 1% 미만 할인도 할인 표시를 하지 않으므로 null 이다.
     *
     * @param list 정가. null 이면 할인 표시가 없다
     * @return 1 이상의 할인율. 정가가 없거나, 판매가 이하이거나, 통화가 다르거나, 1% 미만이면 null
     */
    public static Integer discountRate(Money list, Money sale) {
        Objects.requireNonNull(sale, "sale");
        if (list == null || list.currency() != sale.currency() || list.amount() <= sale.amount()) {
            return null;
        }

        BigDecimal discount = BigDecimal.valueOf(list.amount() - sale.amount());
        int rate = discount.multiply(PERCENT)
            .divide(BigDecimal.valueOf(list.amount()), 0, RoundingMode.DOWN)
            .intValueExact();
        return rate == 0 ? null : rate;
    }
}
