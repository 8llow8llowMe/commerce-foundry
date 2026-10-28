package com.commercefoundry.domainlayer.display.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.display.adapter.in.admin.presenter.DisplayPreviewAdminPresenter;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.port.out.DisplaySectionRepositoryPort;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductLookupProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DisplayPreviewAdminFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private final DisplaySectionRepositoryPort displayPort = mock(DisplaySectionRepositoryPort.class);
    private final SnowflakeIdGenerator ids = mock(SnowflakeIdGenerator.class);
    private final ProductRepositoryPort productPort = mock(ProductRepositoryPort.class);
    private final SkuRepositoryPort skuPort = mock(SkuRepositoryPort.class);
    private final ProductImageRepositoryPort imagePort = mock(ProductImageRepositoryPort.class);
    private final DisplayPreviewAdminFacade facade = new DisplayPreviewAdminFacade(
        new DisplaySectionProcessor(displayPort, ids, MediaUrlPolicy.of(""), Clock.fixed(NOW, ZoneOffset.UTC)),
        new ProductLookupProcessor(productPort),
        new ProductListProcessor(productPort, skuPort, imagePort, new StoreProperties(null)),
        new DisplayPreviewAdminPresenter()
    );

    @Test
    void validatesAllDraftsThenProjectsLiveProductsWithoutDisplayIo() {
        given(productPort.findAllByIdsIncludingHidden(anyCollection())).willReturn(List.of(product(700, true), product(800, false), product(900, true)));
        given(productPort.findVisibleListRowsByIds(anyCollection())).willReturn(List.of(
            new ProductListQueryResult(700, "상품", 39000, CurrencyCode.KRW, null, null, SaleStatus.ON_SALE, false, false)
        ));
        var response = facade.preview(List.of(
            pick(false, null, null, 900), pick(true, NOW, NOW.plusSeconds(1), 700, 800),
            pick(true, null, NOW, 900), pick(true, NOW.plusSeconds(1), null, 900), pick(true, null, null, 800)
        ));

        assertThat(response.sections()).extracting(section -> section.sectionId()).containsExactly("preview-section-1", "preview-section-4");
        assertThat(response.sections().getFirst().items()).extracting(item -> item.itemId()).containsExactly("preview-item-30");
        assertThat(response.sections().getFirst().items().getFirst().product().salePrice().amount()).isEqualTo(39000);
        assertThat(response.sections().get(1).items()).isEmpty();
        verify(productPort).findAllByIdsIncludingHidden(Set.of(700L, 800L, 900L));
        verify(productPort).findVisibleListRowsByIds(Set.of(700L, 800L));
        verify(skuPort).aggregateByProductIds(List.of(700L));
        verify(imagePort).findThumbnailsByProductIds(List.of(700L));
        verify(imagePort).findStillImagesByProductIds(List.of(700L));
        verifyNoInteractions(displayPort, ids);
    }

    @Test
    void missingProductInHiddenDraftFailsBeforeCardLookup() {
        given(productPort.findAllByIdsIncludingHidden(anyCollection())).willReturn(List.of());
        assertThatThrownBy(() -> facade.preview(List.of(pick(false, null, null, 999))))
            .isInstanceOfSatisfying(DisplayException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(DisplayErrorCode.DISPLAY_PRODUCT_NOT_FOUND))
            .hasMessageContaining("999");
        verify(productPort).findAllByIdsIncludingHidden(Set.of(999L));
        verifyNoInteractions(displayPort, ids, skuPort, imagePort);
    }

    @Test
    void hiddenDraftStillHasDomainValidation() {
        var invalid = pick(false, null, null, 700, 700);
        assertThatThrownBy(() -> facade.preview(List.of(invalid))).isInstanceOfSatisfying(
            DisplayException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(DisplayErrorCode.DISPLAY_PRODUCT_DUPLICATED)
        );
        verifyNoInteractions(displayPort, ids, productPort, skuPort, imagePort);
    }

    @Test
    void emptyDraftsDoNotQueryAnything() {
        assertThat(facade.preview(List.of()).sections()).isEmpty();
        verifyNoInteractions(displayPort, ids, productPort, skuPort, imagePort);
    }

    private static DisplaySectionUpsertCommand pick(boolean visible, Instant start, Instant end, long... products) {
        var items = java.util.Arrays.stream(products).mapToObj(id -> DisplaySectionUpsertCommand.Item.builder()
            .itemType(DisplayItemType.PRODUCT).productId(id).build()).toList();
        return DisplaySectionUpsertCommand.builder().sectionType(DisplaySectionType.PRODUCT_PICK)
            .visible(visible).startAt(start).endAt(end).items(items).build();
    }

    private static Product product(long id, boolean visible) {
        return new Product(id, 1L, "상품", null, null, SaleStatus.ON_SALE, visible, Money.of(39000, CurrencyCode.KRW), null, null,
            false, null, null, false, null);
    }
}
