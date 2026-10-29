package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.ProductAdminPresenter;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.*;
import com.commercefoundry.domainlayer.product.domain.model.*;
import com.commercefoundry.shared.commerce.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductAdminDetailProcessorTest {
    private static final long ID = 4_611_686_018_427_387_904L;
    private static final Instant RETIRED = Instant.parse("2026-10-20T00:00:00Z");
    @Mock private ProductRepositoryPort products;
    @Mock private ProductCategoryRepositoryPort categories;
    @Mock private ProductOptionRepositoryPort options;
    @Mock private SkuRepositoryPort skus;
    @Mock private ProductImageRepositoryPort images;
    @Mock private ProductCustomOrderFieldRepositoryPort fields;
    @InjectMocks private ProductAdminDetailProcessor processor;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrDeletedStopsBeforeReadingChildren(boolean deleted) {
        given(products.findAllByIdsIncludingHidden(List.of(ID))).willReturn(deleted ? List.of(product(RETIRED)) : List.of());
        assertThatThrownBy(() -> processor.getProduct(ID)).isInstanceOf(ProductException.class)
            .hasMessageContaining("상품");
        verifyNoInteractions(categories, options, skus, images, fields);
    }

    @Test
    void preservesHiddenRetiredStoppedAndRawEditorValuesWithSevenBulkReads() {
        Product product = product(null);
        given(products.findAllByIdsIncludingHidden(List.of(ID))).willReturn(List.of(product));
        given(categories.findCategoryIdsByProductId(ID)).willReturn(List.of(8L, 3L, 8L));
        List<OptionGroup> groups = new ArrayList<>(List.of(new OptionGroup(ID + 1, ID, "색상", 0, RETIRED)));
        given(options.findGroupsByProductId(ID)).willReturn(groups);
        given(options.findValuesByProductId(ID)).willReturn(List.of(new OptionValue(ID + 2, ID + 1, ID, "검정", 0, null)));
        given(skus.findAllByProductId(ID)).willReturn(List.of(
            new Sku(ID + 3, ID, SkuOptionKey.of(List.of(ID + 2)), Money.of(500, KRW), SaleStatus.STOP_SELLING, null, null)));
        given(images.findAllByProductId(ID)).willReturn(List.of(
            new ProductImage(ID + 4, ID, ImageType.VIDEO, "https://example.com/video.mp4", null, null, null, 0, "https://example.com/poster.jpg")));
        given(fields.findAllByProductId(ID)).willReturn(List.of(
            new CustomOrderField(1, ID, "chest", "가슴둘레", CustomOrderInputType.NUMBER, "cm", true,
                new BigDecimal("60"), new BigDecimal("150"), new BigDecimal("0.5"), null, 0)));

        ProductAdminDetailInfo info = processor.getProduct(ID);
        groups.clear();
        var response = new ProductAdminPresenter().toDetailResponse(info);

        assertThat(info.categoryIds()).containsExactly(3L, 5L, 8L);
        assertThatThrownBy(() -> info.optionGroups().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(response.productId()).isEqualTo(String.valueOf(ID));
        assertThat(response.createdByMemberId()).isEqualTo(String.valueOf(ID + 9));
        assertThat(response.visible()).isFalse();
        assertThat(response.salePrice().amount()).isEqualTo(10000);
        assertThat(response.listPrice().amount()).isEqualTo(9000); // 고객 할인 표시라면 감춰질 정가도 보존
        assertThat(response.preorder()).isTrue();
        assertThat(response.expectedShipDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(response.customOrderNotice()).isEqualTo("저장된 안내");
        assertThat(response.optionGroups()).singleElement().satisfies(group -> {
            assertThat(group.optionGroupId()).isEqualTo(String.valueOf(ID + 1));
            assertThat(group.retiredAt()).isEqualTo(RETIRED);
            assertThat(group.values()).singleElement().satisfies(value -> {
                assertThat(value.optionValueId()).isEqualTo(String.valueOf(ID + 2));
                assertThat(value.retiredAt()).isNull();
            });
        });
        assertThat(response.skus()).singleElement().satisfies(sku -> {
            assertThat(sku.optionValueIds()).containsExactly(String.valueOf(ID + 2));
            assertThat(sku.saleStatus().code()).isEqualTo("STOP_SELLING");
            assertThat(sku.additionalPrice().amount()).isEqualTo(500);
            assertThat(sku.weightGrams()).isNull();
            assertThat(sku.hsCode()).isNull();
        });
        assertThat(response.images()).singleElement().satisfies(image -> assertThat(image.imageType().code()).isEqualTo("VIDEO"));
        assertThat(response.customOrderFields()).singleElement().satisfies(field -> {
            assertThat(field.inputType().code()).isEqualTo("NUMBER");
            assertThat(field.stepValue()).isEqualByComparingTo("0.5");
        });
        verify(products).findAllByIdsIncludingHidden(List.of(ID));
        verify(categories).findCategoryIdsByProductId(ID);
        verify(options).findGroupsByProductId(ID);
        verify(options).findValuesByProductId(ID);
        verify(skus).findAllByProductId(ID);
        verify(images).findAllByProductId(ID);
        verify(fields).findAllByProductId(ID);
        verifyNoMoreInteractions(products, categories, options, skus, images, fields);
    }

    @Test
    void optionalValuesAndEmptyChildrenRemainAbsent() {
        Product product = new Product(ID, 5, "상품", null, null, SaleStatus.ON_SALE, true, Money.of(100, KRW), null,
            null, false, null, null, false, null, 0L, null, null);
        given(products.findAllByIdsIncludingHidden(List.of(ID))).willReturn(List.of(product));
        var response = new ProductAdminPresenter().toDetailResponse(processor.getProduct(ID));
        assertThat(response.categoryIds()).containsExactly("5");
        assertThat(response.createdByMemberId()).isNull();
        assertThat(response.listPrice()).isNull();
        assertThat(response.optionGroups()).isEmpty();
        assertThat(response.skus()).isEmpty();
        assertThat(response.images()).isEmpty();
        assertThat(response.customOrderFields()).isEmpty();
    }

    private static Product product(Instant deletedAt) {
        return new Product(ID, 5, "숨김 상품", "요약", "<p>설명</p>", SaleStatus.STOP_SELLING, false,
            Money.of(10000, KRW), Money.of(9000, KRW), 4, true, "저장된 안내", 14,
            true, LocalDate.of(2026, 12, 1), 7L, deletedAt, ID + 9);
    }
}
