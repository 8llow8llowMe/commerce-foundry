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
     * 상품 단위 판매 가능 상태 (목록 뱃지 · 상세 상단). 아래 순서로 처음 맞는 것이다.
     *
     * <ol>
     *   <li>상품이 판매 종료면 판매 종료</li>
     *   <li>판매중(ON_SALE) SKU 가 없으면(0건 포함) 판매 종료 — 팔 수 있는 조합이 없는 것은 품절이 아니라 판매를 하지 않는 것이다</li>
     *   <li>구매 가능한 SKU 가 있으면 판매중</li>
     *   <li>그 밖(판매중 SKU 는 있지만 모두 재고가 없다)이면 품절</li>
     * </ol>
     *
     * <p>재고 테이블이 생기기 전(#8)에는 모든 SKU 가 {@link StockSnapshot#unlimited()} 라 {@code hasPurchasableSku = hasOnSaleSku} 이고,
     * 그래서 품절은 나오지 않는다. 재고 이슈(#10)가 판매중 SKU 의 재고(stock)로 {@code hasPurchasableSku} 를 계산하면 품절이 나온다.
     *
     * @param hasOnSaleSku 판매중(ON_SALE) SKU 가 하나라도 있는가
     * @param hasPurchasableSku 판매중이면서 품절이 아닌 SKU 가 하나라도 있는가 — {@link #ofSku} 가 판매중을 내는 SKU
     */
    public static SaleAvailability ofProduct(SaleStatus productStatus, boolean hasOnSaleSku, boolean hasPurchasableSku) {
        Objects.requireNonNull(productStatus, "productStatus");

        if (productStatus == SaleStatus.STOP_SELLING || !hasOnSaleSku) {
            return STOP_SELLING;
        }
        return hasPurchasableSku ? ON_SALE : SOLD_OUT;
    }
}
