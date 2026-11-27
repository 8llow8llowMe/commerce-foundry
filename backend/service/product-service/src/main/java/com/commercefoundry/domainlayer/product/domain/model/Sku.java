package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 판매 단위 (상품 × 옵션 값 조합). 판매가는 {@link ProductPricing#skuSalePrice} 로 계산한다.
 *
 * <p>무게 · HS 코드는 해외 배송비 견적과 통관 신고에 쓴다. 해외 판매 상품만 필수라 비어 있을 수 있고, product-service 는 빈 값을 그대로 내린다 —
 * 해외 주문에서 거절하는 쪽은 order-service 다 ({@code order-flow-guide.md} §2-2).
 *
 * @param optionKey 옵션 값 조합 키. 옵션 없는 상품은 빈 키
 * @param additionalPrice 옵션 추가금 (0 이상). 통화는 상품 기본 판매가와 같다
 * @param weightGrams 배송 무게 (그램, 1 이상). null 이면 미입력
 * @param hsCode 통관 신고용 HS 코드. null 이면 미입력
 * @param barcode 포장 검수 바코드. null 이면 미등록
 */
public record Sku(
    long id, long productId, SkuOptionKey optionKey, Money additionalPrice, SaleStatus saleStatus, Integer weightGrams, HsCode hsCode, SkuBarcode barcode
) {

    /**
     * @throws IllegalArgumentException 무게가 있는데 1 미만일 때
     */
    public Sku {
        Objects.requireNonNull(optionKey, "optionKey");
        Objects.requireNonNull(additionalPrice, "additionalPrice");
        Objects.requireNonNull(saleStatus, "saleStatus");
        if (weightGrams != null && weightGrams <= 0) {
            throw new IllegalArgumentException("SKU 무게는 1g 이상이어야 합니다: " + weightGrams);
        }
    }

    public Sku(long id, long productId, SkuOptionKey optionKey, Money additionalPrice, SaleStatus saleStatus, Integer weightGrams, HsCode hsCode) {
        this(id, productId, optionKey, additionalPrice, saleStatus, weightGrams, hsCode, null);
    }

    /** 옵션 구성·아이디·바코드를 유지하고 기존 편집 속성을 교체한다. */
    public Sku withEditableProperties(Money price, SaleStatus status, Integer weight, HsCode code) {
        return withEditableProperties(price, status, weight, code, barcode);
    }

    /** 옵션 구성·아이디를 유지하고 바코드를 포함한 편집 속성을 교체한다. */
    public Sku withEditableProperties(Money price, SaleStatus status, Integer weight, HsCode code, SkuBarcode updatedBarcode) {
        return new Sku(id, productId, optionKey, price, status, weight, code, updatedBarcode);
    }

    /** 판매중(ON_SALE) SKU 인가. 상품 판매 상태 · 재고는 보지 않는다 — 판매 가능 여부는 {@link SaleAvailability#ofSku} 가 정한다. */
    public boolean isOnSale() {
        return saleStatus == SaleStatus.ON_SALE;
    }
}
