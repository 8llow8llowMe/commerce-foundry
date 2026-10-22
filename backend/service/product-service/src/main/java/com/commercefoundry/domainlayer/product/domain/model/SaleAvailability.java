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
     * 재고를 보기 전의 구매 가능 조건 — 상품이 삭제되지 않았고 노출 중이며, 상품 · SKU 가 모두 판매중(ON_SALE)이다.
     * 재고 선점은 이 조건으로 판매 불가를 거르고, 수량은 조건부 차감이 판정한다.
     *
     * <p><b>서비스 간 SKU 조회의 {@code purchasable} 과 재고 선점의 판매 불가({@code PRODUCT_022})는 이 한 곳에서 판정한다.</b>
     * 저장된 삭제 상품은 DB check 로 숨김 · 판매 종료라 앞 조건에도 걸리지만, 삭제 조건을 check 에 기대지 않고 여기서도 본다 —
     * 숨김 · 삭제 상품까지 읽는 조회({@code findAllByIdsIncludingHidden})는 삭제 상품을 걸러 주지 않는다.
     */
    public static boolean isOpenForSale(Product product, Sku sku) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(sku, "sku");

        return !product.isDeleted() && product.visible() && product.saleStatus() == SaleStatus.ON_SALE && sku.isOnSale();
    }

    /**
     * 지금 주문할 수 있는 SKU 인가 — {@link #isOpenForSale} 이고 품절이 아니다. 서비스 간 SKU 조회의 {@code purchasable} 이다.
     * {@code ofSku(...) == ON_SALE} 에 노출 · 삭제 조건을 더한 것과 같다.
     */
    public static boolean isPurchasable(Product product, Sku sku, StockSnapshot stock) {
        Objects.requireNonNull(stock, "stock");

        return isOpenForSale(product, sku) && !stock.isSoldOut();
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
     * <p>{@code hasPurchasableSku} 는 목록 · 상세가 같은 규칙으로 계산한다 — 판매중 SKU 이면서 재고가 무제한이거나 판매 가능 수량이 0 보다 크다.
     * 재고 행이 없는 SKU 는 품절이다 ({@link StockSnapshot#empty()}). 목록은 SKU 집계 쿼리의 stock left join 이, 상세는 {@code ProductSaleSummary#ofSkus} 가 계산한다.
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
