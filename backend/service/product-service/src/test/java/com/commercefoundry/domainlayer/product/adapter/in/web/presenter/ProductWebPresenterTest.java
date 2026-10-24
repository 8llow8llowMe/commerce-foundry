package com.commercefoundry.domainlayer.product.adapter.in.web.presenter;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CategoryPathItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CustomOrderFieldItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.PreorderItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductImageItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductOptionGroupItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductOptionValueItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductSkuItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductThumbnailItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductDetailResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductListItemResponse;
import com.commercefoundry.domainlayer.product.application.info.CategoryPathInfo;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderFieldInfo;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderInfo;
import com.commercefoundry.domainlayer.product.application.info.PreorderInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductDetailInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductImageInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductListItemInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductOptionGroupInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductOptionValueInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductSkuInfo;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.persistence.dto.SliceResponse;
import com.commercefoundry.shared.commerce.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

class ProductWebPresenterTest {

    /** 자바스크립트 Number 안전 정수(2^53 − 1)를 넘는 Snowflake 아이디 — 숫자로 내리면 반올림된다. */
    private static final long SNOWFLAKE_ID = 4611686018427387904L;
    private static final CodeNameDescriptionMetadata ON_SALE = CodeNameDescriptionMetadata.of("ON_SALE", "판매중", "구매할 수 있습니다.");

    private final ProductWebPresenter presenter = new ProductWebPresenter();

    @Test
    @DisplayName("목록은 SliceResponse.map 으로 항목만 바꾸고 hasNext 를 그대로 둔다 — 아이디는 문자열, 금액은 MoneyItem, 판매 상태는 metadata 다")
    void listResponse() {
        ProductListItemInfo info = ProductListItemInfo.builder()
            .productId(SNOWFLAKE_ID)
            .name("저고리")
            .thumbnail(new ProductImageInfo(SNOWFLAKE_ID + 1, ImageType.THUMBNAIL, "https://cdn.example.com/t.jpg", 600, null, "정면", 0, null))
            .salePrice(Money.of(39_000, KRW))
            .listPrice(Money.of(45_000, KRW))
            .discountRate(13)
            .hasOptionSurcharge(true)
            .saleStatus(SaleAvailability.ON_SALE)
            .customOrder(true)
            .preorder(true)
            .build();

        SliceResponse<ProductListItemResponse> response = presenter.toListResponse(new SliceImpl<>(List.of(info), PageRequest.of(0, 20), true));

        assertThat(response.hasNext()).isTrue();
        assertThat(response.contents()).containsExactly(ProductListItemResponse.builder()
            .productId("4611686018427387904")
            .name("저고리")
            .thumbnail(ProductThumbnailItem.builder()
                .imageId("4611686018427387905").url("https://cdn.example.com/t.jpg").width(600).height(null).altText("정면").build())
            .salePrice(MoneyItem.of(39_000, "KRW"))
            .listPrice(MoneyItem.of(45_000, "KRW"))
            .discountRate(13)
            .hasOptionSurcharge(true)
            .saleStatus(ON_SALE)
            .customOrder(true)
            .preorder(true)
            .build());
    }

    @Test
    @DisplayName("없는 값(대표 이미지 · 정가 · 할인율)은 null 그대로다")
    void listResponseKeepsNulls() {
        ProductListItemInfo info = ProductListItemInfo.builder()
            .productId(1L).name("하의").salePrice(Money.of(8_950, USD)).saleStatus(SaleAvailability.STOP_SELLING).build();

        ProductListItemResponse item = presenter.toListResponse(new SliceImpl<>(List.of(info), PageRequest.of(0, 20), false)).contents().getFirst();

        assertThat(item.thumbnail()).isNull();
        assertThat(item.listPrice()).isNull();
        assertThat(item.discountRate()).isNull();
        assertThat(item.preorder()).isFalse();
        assertThat(item.salePrice()).isEqualTo(MoneyItem.of(8_950, "USD"));
        assertThat(item.saleStatus().code()).isEqualTo("STOP_SELLING");
    }

    @Test
    @DisplayName("빈 목록은 빈 contents 다")
    void emptyList() {
        SliceResponse<ProductListItemResponse> response = presenter.toListResponse(new SliceImpl<>(List.of(), PageRequest.of(3, 20), false));

        assertThat(response.contents()).isEmpty();
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    @DisplayName("상세의 모든 아이디(상품 · 카테고리 · 이미지 · 옵션 그룹 · 값 · SKU · SKU 의 값 목록)는 문자열이고 순서를 그대로 둔다. 영상은 VIDEO · 포스터, 이미지는 IMAGE 다")
    void detailResponse() {
        ProductDetailInfo info = ProductDetailInfo.builder()
            .productId(SNOWFLAKE_ID)
            .name("저고리")
            .summary("요약")
            .categoryPath(List.of(new CategoryPathInfo(101, "상의"), new CategoryPathInfo(102, "저고리")))
            .thumbnail(new ProductImageInfo(88000, ImageType.THUMBNAIL, "https://cdn.example.com/t.jpg", 600, 800, "대표", 0, null))
            .images(List.of(
                new ProductImageInfo(88001, ImageType.GALLERY, "https://cdn.example.com/1.jpg", 1200, 1600, "정면", 1, null),
                new ProductImageInfo(88002, ImageType.VIDEO, "https://cdn.example.com/2.mp4", 1080, 1920, "착용 영상", 1, "https://cdn.example.com/2.jpg")
            ))
            .descriptionHtml("<p>설명</p>")
            .salePrice(Money.of(39_000, KRW))
            .listPrice(null)
            .discountRate(null)
            .saleStatus(SaleAvailability.ON_SALE)
            .maxQuantityPerOrder(null)
            .optionGroups(List.of(new ProductOptionGroupInfo(502, "사이즈", 1, List.of(new ProductOptionValueInfo(9001, "M", 1)))))
            .skus(List.of(new ProductSkuInfo(SNOWFLAKE_ID + 9, List.of(9001L, 5011L), Money.of(5_000, KRW), Money.of(44_000, KRW), SaleAvailability.ON_SALE)))
            .customOrder(new CustomOrderInfo("고지", 14, List.of(CustomOrderFieldInfo.builder()
                .fieldKey("chest").label("가슴둘레").inputType(CustomOrderInputType.NUMBER).unit("cm").required(true)
                .min(new BigDecimal("60")).max(new BigDecimal("150")).step(new BigDecimal("0.5")).helpText(null)
                .build())))
            .preorder(new PreorderInfo(LocalDate.of(2026, 11, 20)))
            .build();

        ProductDetailResponse response = presenter.toDetailResponse(info);

        assertThat(response.productId()).isEqualTo("4611686018427387904");
        assertThat(response.categoryPath()).containsExactly(
            CategoryPathItem.builder().categoryId("101").name("상의").build(),
            CategoryPathItem.builder().categoryId("102").name("저고리").build()
        );
        assertThat(response.thumbnail().imageId()).isEqualTo("88000");
        assertThat(response.images()).containsExactly(ProductImageItem.builder()
            .imageId("88001").mediaType("IMAGE").url("https://cdn.example.com/1.jpg").posterUrl(null)
            .width(1200).height(1600).altText("정면").displayOrder(1).build(), ProductImageItem.builder()
            .imageId("88002").mediaType("VIDEO").url("https://cdn.example.com/2.mp4").posterUrl("https://cdn.example.com/2.jpg")
            .width(1080).height(1920).altText("착용 영상").displayOrder(1).build());
        assertThat(response.salePrice()).isEqualTo(MoneyItem.of(39_000, "KRW"));
        assertThat(response.listPrice()).isNull();
        assertThat(response.discountRate()).isNull();
        assertThat(response.maxQuantityPerOrder()).isNull();
        assertThat(response.saleStatus()).isEqualTo(ON_SALE);
        assertThat(response.optionGroups()).containsExactly(ProductOptionGroupItem.builder()
            .optionGroupId("502").name("사이즈").displayOrder(1)
            .values(List.of(ProductOptionValueItem.builder().optionValueId("9001").name("M").displayOrder(1).build()))
            .build());
        assertThat(response.skus()).containsExactly(ProductSkuItem.builder()
            .skuId("4611686018427387913")
            .optionValueIds(List.of("9001", "5011"))
            .additionalPrice(MoneyItem.of(5_000, "KRW"))
            .salePrice(MoneyItem.of(44_000, "KRW"))
            .saleStatus(ON_SALE)
            .build());
        assertThat(response.preorder()).isEqualTo(PreorderItem.builder().expectedShipDate(LocalDate.of(2026, 11, 20)).build());
        assertThat(response.customOrder().notice()).isEqualTo("고지");
        assertThat(response.customOrder().leadTimeDays()).isEqualTo(14);
        assertThat(response.customOrder().fields()).containsExactly(CustomOrderFieldItem.builder()
            .fieldKey("chest").label("가슴둘레").inputType("NUMBER").unit("cm").required(true)
            .min(new BigDecimal("60")).max(new BigDecimal("150")).step(new BigDecimal("0.5")).helpText(null)
            .build());
    }

    @Test
    @DisplayName("맞춤 제작 · 프리오더 안내 · 대표 이미지가 없으면 null 그대로다")
    void detailKeepsNulls() {
        ProductDetailInfo info = ProductDetailInfo.builder()
            .productId(1L).name("하의").categoryPath(List.of()).images(List.of()).salePrice(Money.of(1_000, KRW))
            .saleStatus(SaleAvailability.STOP_SELLING).optionGroups(List.of()).skus(List.of())
            .build();

        ProductDetailResponse response = presenter.toDetailResponse(info);

        assertThat(response.thumbnail()).isNull();
        assertThat(response.customOrder()).isNull();
        assertThat(response.preorder()).isNull();
        assertThat(response.skus()).isEmpty();
        assertThat(response.optionGroups()).isEmpty();
    }
}
