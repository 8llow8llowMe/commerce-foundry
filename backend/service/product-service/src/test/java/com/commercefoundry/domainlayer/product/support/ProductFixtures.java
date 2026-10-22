package com.commercefoundry.domainlayer.product.support;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import com.commercefoundry.domainlayer.product.domain.model.SkuStocks;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.shared.commerce.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 상품 도메인 테스트 데이터. 금액은 KRW 다 — 기본 판매가 39,000원 · 정가 45,000원.
 */
public final class ProductFixtures {

    public static final long BASE_PRICE = 39_000L;
    public static final long LIST_PRICE = 45_000L;
    /** 대표 카테고리. */
    public static final long CATEGORY_ID = 11L;
    /** {@link #stock} 의 판매 가능 수량. */
    public static final long IN_STOCK_QUANTITY = 10L;
    /** 삭제 상품 · 은퇴 옵션의 삭제 · 은퇴 시각. */
    public static final Instant REMOVED_AT = Instant.parse("2026-10-20T00:00:00Z");

    private ProductFixtures() {
    }

    /** 노출 중 · 판매중 · 맞춤 제작 아님 · 1회 최대 10개. */
    public static Product product(long id) {
        return product(id, SaleStatus.ON_SALE, true, false);
    }

    /** 대표 카테고리만 다른 상품. */
    public static Product product(long id, long categoryId) {
        return product(id, categoryId, SaleStatus.ON_SALE, true, false, null);
    }

    public static Product product(long id, SaleStatus saleStatus, boolean visible, boolean customOrder) {
        return product(id, CATEGORY_ID, saleStatus, visible, customOrder, null);
    }

    /** 삭제(soft delete)된 상품 — DB check({@code ck_product_deleted_hidden})가 허용하는 유일한 모양인 숨김 · 판매 종료다. */
    public static Product deletedProduct(long id) {
        return deletedProduct(id, SaleStatus.STOP_SELLING, false);
    }

    /** 삭제 시각만 있고 노출 · 판매 상태는 고른 상품 — DB check 를 비켜 온 행에서도 삭제 조건 하나로 막히는지 볼 때 쓴다. */
    public static Product deletedProduct(long id, SaleStatus saleStatus, boolean visible) {
        return product(id, CATEGORY_ID, saleStatus, visible, false, REMOVED_AT);
    }

    private static Product product(long id, long categoryId, SaleStatus saleStatus, boolean visible, boolean customOrder, Instant deletedAt) {
        return new Product(
            id, categoryId, "상품" + id, "요약" + id, "<p>설명</p>",
            saleStatus, visible, Money.of(BASE_PRICE, KRW), Money.of(LIST_PRICE, KRW), 10,
            customOrder, customOrder ? "제작 착수 후 취소 불가" : null, customOrder ? 14 : null,
            false, null, 0L, deletedAt, null
        );
    }

    /** 노출 중 · 판매중 프리오더 상품. @param expectedShipDate 발송 예정일. null 이면 "발송일 추후 안내" */
    public static Product preorderProduct(long id, LocalDate expectedShipDate) {
        return new Product(
            id, CATEGORY_ID, "상품" + id, "요약" + id, "<p>설명</p>",
            SaleStatus.ON_SALE, true, Money.of(BASE_PRICE, KRW), Money.of(LIST_PRICE, KRW), 10,
            false, null, null,
            true, expectedShipDate, 0L, null, null
        );
    }

    /** 무게 · HS 코드가 없는(국내 판매 전용) SKU. @param optionKey 저장 정규형 조합 키 ({@code "5011,9001"}). 옵션 없는 상품은 {@code ""} */
    public static Sku sku(long id, long productId, String optionKey, long additionalAmount, SaleStatus saleStatus) {
        return sku(id, productId, optionKey, additionalAmount, saleStatus, null, null);
    }

    /** 무게 · HS 코드까지 정한 SKU. 둘 다 null 이면 미입력이다. */
    public static Sku sku(
        long id, long productId, String optionKey, long additionalAmount, SaleStatus saleStatus, Integer weightGrams, String hsCode
    ) {
        return new Sku(
            id, productId, SkuOptionKey.fromStored(optionKey), Money.of(additionalAmount, KRW), saleStatus,
            weightGrams, hsCode == null ? null : new HsCode(hsCode)
        );
    }

    /** 재고 관리 · 판매 가능 수량 {@link #IN_STOCK_QUANTITY} 인 재고 행. */
    public static Stock stock(long skuId) {
        return new Stock(skuId, true, IN_STOCK_QUANTITY);
    }

    /** 주어진 SKU 마다 재고가 있는 재고 묶음. 인자가 없으면 재고 행이 하나도 없다 (모두 품절). */
    public static SkuStocks inStock(long... skuIds) {
        return SkuStocks.of(stocks(skuIds));
    }

    /** 주어진 SKU 마다 재고가 있는 재고 행 목록 — 포트 응답 스텁용. */
    public static List<Stock> stocks(long... skuIds) {
        return Arrays.stream(skuIds).mapToObj(ProductFixtures::stock).toList();
    }

    public static OptionGroup group(long id, long productId, String name, int displayOrder) {
        return new OptionGroup(id, productId, name, displayOrder, null);
    }

    /** 은퇴한 옵션 그룹. */
    public static OptionGroup retiredGroup(long id, long productId, String name, int displayOrder) {
        return new OptionGroup(id, productId, name, displayOrder, REMOVED_AT);
    }

    public static OptionValue value(long id, long groupId, long productId, String name, int displayOrder) {
        return new OptionValue(id, groupId, productId, name, displayOrder, null);
    }

    /** 은퇴한 옵션 값. */
    public static OptionValue retiredValue(long id, long groupId, long productId, String name, int displayOrder) {
        return new OptionValue(id, groupId, productId, name, displayOrder, REMOVED_AT);
    }

    public static ProductImage image(long id, long productId, ImageType imageType, int displayOrder) {
        return new ProductImage(id, productId, imageType, "https://cdn.example.com/" + id + ".jpg", 600, 800, "이미지" + id, displayOrder, null);
    }

    /** 상세 갤러리 영상 — 포스터 이미지가 있다. */
    public static ProductImage video(long id, long productId, int displayOrder) {
        return new ProductImage(
            id, productId, ImageType.VIDEO, "https://cdn.example.com/" + id + ".mp4", 1080, 1920, "영상" + id, displayOrder,
            "https://cdn.example.com/" + id + "-poster.jpg"
        );
    }

    public static CustomOrderField numberField(long id, long productId, String fieldKey, int displayOrder) {
        return new CustomOrderField(
            id, productId, fieldKey, "가슴둘레", CustomOrderInputType.NUMBER, "cm",
            true, new BigDecimal("60"), new BigDecimal("150"), new BigDecimal("0.5"), "겨드랑이 아래", displayOrder
        );
    }
}
