package com.commercefoundry.domainlayer.product.support;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import com.commercefoundry.shared.commerce.Money;
import java.math.BigDecimal;

/**
 * 상품 도메인 테스트 데이터. 금액은 KRW 다 — 기본 판매가 39,000원 · 정가 45,000원.
 */
public final class ProductFixtures {

    public static final long BASE_PRICE = 39_000L;
    public static final long LIST_PRICE = 45_000L;
    /** 대표 카테고리. */
    public static final long CATEGORY_ID = 11L;

    private ProductFixtures() {
    }

    /** 노출 중 · 판매중 · 맞춤 제작 아님 · 1회 최대 10개. */
    public static Product product(long id) {
        return product(id, SaleStatus.ON_SALE, true, false);
    }

    /** 대표 카테고리만 다른 상품. */
    public static Product product(long id, long categoryId) {
        return product(id, categoryId, SaleStatus.ON_SALE, true, false);
    }

    public static Product product(long id, SaleStatus saleStatus, boolean visible, boolean customOrder) {
        return product(id, CATEGORY_ID, saleStatus, visible, customOrder);
    }

    private static Product product(long id, long categoryId, SaleStatus saleStatus, boolean visible, boolean customOrder) {
        return new Product(
            id, categoryId, "상품" + id, "요약" + id, "<p>설명</p>",
            saleStatus, visible, Money.of(BASE_PRICE, KRW), Money.of(LIST_PRICE, KRW), 10,
            customOrder, customOrder ? "제작 착수 후 취소 불가" : null, customOrder ? 14 : null
        );
    }

    /** @param optionKey 저장 정규형 조합 키 ({@code "5011,9001"}). 옵션 없는 상품은 {@code ""} */
    public static Sku sku(long id, long productId, String optionKey, long additionalAmount, SaleStatus saleStatus) {
        return new Sku(id, productId, SkuOptionKey.fromStored(optionKey), Money.of(additionalAmount, KRW), saleStatus);
    }

    public static OptionGroup group(long id, long productId, String name, int displayOrder) {
        return new OptionGroup(id, productId, name, displayOrder);
    }

    public static OptionValue value(long id, long groupId, long productId, String name, int displayOrder) {
        return new OptionValue(id, groupId, productId, name, displayOrder);
    }

    public static ProductImage image(long id, long productId, ImageType imageType, int displayOrder) {
        return new ProductImage(id, productId, imageType, "https://cdn.example.com/" + id + ".jpg", 600, 800, "이미지" + id, displayOrder);
    }

    public static CustomOrderField numberField(long id, long productId, String fieldKey, int displayOrder) {
        return new CustomOrderField(
            id, productId, fieldKey, "가슴둘레", CustomOrderInputType.NUMBER, "cm",
            true, new BigDecimal("60"), new BigDecimal("150"), new BigDecimal("0.5"), "겨드랑이 아래", displayOrder
        );
    }
}
