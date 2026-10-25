package com.commercefoundry.domainlayer.display.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.display.adapter.in.web.dto.response.DisplayMainResponse;
import com.commercefoundry.domainlayer.display.adapter.in.web.presenter.DisplayWebPresenter;
import com.commercefoundry.domainlayer.display.application.port.out.DisplaySectionRepositoryPort;
import com.commercefoundry.domainlayer.display.application.service.processor.DisplaySectionProcessor;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 고객 메인의 조회 수 — 섹션 1 · 아이템 1 · 상품 카드 3(상품 행 · SKU 집계 · 대표 이미지, 모든 섹션의 상품 아이디를 합쳐 한 번)이다.
 * 실제 Processor 들을 가짜 포트에 붙여 포트 호출 수를 센다.
 */
class DisplayWebFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    private final DisplaySectionRepositoryPort displayPort = mock(DisplaySectionRepositoryPort.class);
    private final ProductRepositoryPort productPort = mock(ProductRepositoryPort.class);
    private final SkuRepositoryPort skuPort = mock(SkuRepositoryPort.class);
    private final ProductImageRepositoryPort imagePort = mock(ProductImageRepositoryPort.class);

    private final DisplayWebFacade facade = new DisplayWebFacade(
        new DisplaySectionProcessor(displayPort, new SnowflakeIdGenerator(1, 1), MediaUrlPolicy.of(""), Clock.fixed(NOW, ZoneOffset.UTC)),
        new ProductListProcessor(productPort, skuPort, imagePort, new StoreProperties(null)),
        new DisplayWebPresenter()
    );

    @Test
    @DisplayName("여러 섹션의 상품을 한 번에 읽고(아이디 합집합), 숨긴 상품(행 없음)의 아이템은 빠진다 — 섹션 · 아이템 · 상품 행 · 집계 · 이미지 각 1번")
    void queriesOncePerKind() {
        given(displayPort.findAll()).willReturn(List.of(section(1L, 0), section(2L, 1)));
        given(displayPort.findItemsBySectionIds(anyCollection())).willReturn(List.of(
            product(10L, 1L, 0, 700L), product(11L, 1L, 1, 800L), product(20L, 2L, 0, 800L), product(21L, 2L, 1, 900L)
        ));
        given(productPort.findVisibleListRowsByIds(anyCollection())).willReturn(List.of(row(700L), row(800L)));

        DisplayMainResponse response = facade.getMain();

        assertThat(response.sections().get(0).items()).extracting(item -> item.product().productId()).containsExactly("700", "800");
        assertThat(response.sections().get(1).items()).extracting(item -> item.product().productId()).as("숨긴 900 은 빠진다").containsExactly("800");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(displayPort, times(1)).findAll();
        verify(displayPort, times(1)).findItemsBySectionIds(anyCollection());
        verify(productPort, times(1)).findVisibleListRowsByIds(ids.capture());
        verify(skuPort, times(1)).aggregateByProductIds(List.of(700L, 800L));
        verify(imagePort, times(1)).findThumbnailsByProductIds(List.of(700L, 800L));
        verify(imagePort, times(1)).findStillImagesByProductIds(List.of(700L, 800L));
        assertThat(ids.getValue()).containsExactly(700L, 800L, 900L);
    }

    @Test
    @DisplayName("상품 아이템이 없으면 상품 쪽 조회를 하지 않는다")
    void noProductsNoProductQueries() {
        given(displayPort.findAll()).willReturn(List.of());

        assertThat(facade.getMain().sections()).isEmpty();
        verify(productPort, times(0)).findVisibleListRowsByIds(anyCollection());
        verify(skuPort, times(0)).aggregateByProductIds(anyCollection());
    }

    private static DisplaySection section(long id, int displayOrder) {
        return new DisplaySection(id, DisplaySectionType.PRODUCT_PICK, "픽" + id, null, null, null, null, displayOrder, true, null, null);
    }

    private static DisplayItem product(long id, long sectionId, int displayOrder, long productId) {
        return new DisplayItem(id, sectionId, displayOrder, DisplayItemType.PRODUCT, null, productId, null);
    }

    private static ProductListQueryResult row(long productId) {
        return new ProductListQueryResult(productId, "상품" + productId, 39_000L, CurrencyCode.KRW, null, null, SaleStatus.ON_SALE, false, false);
    }
}
