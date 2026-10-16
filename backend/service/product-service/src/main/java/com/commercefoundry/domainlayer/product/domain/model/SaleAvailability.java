package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 화면에 내는 판매 가능 상태. 저장하지 않고 판매 상태({@link SaleStatus})와 재고에서 유도한다.
 *
 * <p>판매 종료가 품절보다 앞선다 — 판매를 끝낸 상품은 재고가 남아도, 다 팔렸어도 "판매 종료" 다.
 */
@Getter
@RequiredArgsConstructor
public enum SaleAvailability implements CodeNameDescribable {

    ON_SALE("판매중", "구매할 수 있습니다."),
    SOLD_OUT("품절", "재고가 모두 소진되었습니다."),
    STOP_SELLING("판매 종료", "판매가 종료된 상품입니다.");

    private final String displayName;
    private final String description;

    /**
     * SKU 하나의 판매 가능 상태. 상품 · SKU 중 하나라도 판매 종료면 판매 종료, 아니면 재고가 바닥났을 때 품절이다.
     */
    public static SaleAvailability ofSku(SaleStatus productStatus, SaleStatus skuStatus, StockSnapshot stock) {
        Objects.requireNonNull(productStatus, "productStatus");
        Objects.requireNonNull(skuStatus, "skuStatus");
        Objects.requireNonNull(stock, "stock");

        if (productStatus == SaleStatus.STOP_SELLING || skuStatus == SaleStatus.STOP_SELLING) {
            return STOP_SELLING;
        }
        return stock.isSoldOut() ? SOLD_OUT : ON_SALE;
    }

    /**
     * 상품 단위 판매 가능 상태 (목록 뱃지 · 상세 상단).
     *
     * @param hasSellableSku 살 수 있는 SKU 가 하나라도 있는가. SKU 가 0건이면 false 다 — 살 수 있는 것이 없으므로 품절로 보인다
     */
    public static SaleAvailability ofProduct(SaleStatus productStatus, boolean hasSellableSku) {
        Objects.requireNonNull(productStatus, "productStatus");

        if (productStatus == SaleStatus.STOP_SELLING) {
            return STOP_SELLING;
        }
        return hasSellableSku ? ON_SALE : SOLD_OUT;
    }
}
