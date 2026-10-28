package com.commercefoundry.domainlayer.look.application.service;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookDetailResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.ProductLookListResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.presenter.LookWebPresenter;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.application.service.processor.LookWebProcessor;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.persistence.dto.SliceResponse;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

/**
 * 고객 룩 조회 흐름 — 상품 카드는 모든 룩의 상품을 합쳐 한 번에, 상품별 룩은 상품 확인(404 PRODUCT_001)이 플래그보다 먼저.
 */
class LookWebFacadeTest {

    private static final LookImage IMAGE = new LookImage("https://cdn.example.com/media/a.jpg", null, null, null);

    private final LookRepositoryPort port = mock(LookRepositoryPort.class);
    private final ProductListProcessor productListProcessor = mock(ProductListProcessor.class);
    private final ProductDetailProcessor productDetailProcessor = mock(ProductDetailProcessor.class);

    @Test
    @DisplayName("목록 — 모든 룩의 상품 아이디를 합쳐 상품 카드를 한 번 읽고, 숨긴 상품은 빠진다. 아이디는 문자열이다")
    void listReadsProductsOnce() {
        given(port.findVisibleSlice(0, 20)).willReturn(new SliceImpl<>(List.of(look(1L), look(2L)), PageRequest.of(0, 20), true));
        given(port.findItemsByLookIds(anyCollection())).willReturn(List.of(new LookItem(11L, 1L, 100L, 0), new LookItem(12L, 1L, 200L, 1), new LookItem(21L, 2L, 100L, 0)));
        given(productListProcessor.summarizeVisible(anyCollection())).willReturn(Map.of(100L, summary(100L)));

        SliceResponse<?> response = facade(true).getLooks(0, 20);

        assertThat(response.hasNext()).isTrue();
        assertThat(response.contents()).hasSize(2);
        verify(productListProcessor, times(1)).summarizeVisible(Set.of(100L, 200L));
    }

    @Test
    @DisplayName("상세 — 구성 상품 카드만 내고 숨긴 상품은 빠진다")
    void detail() {
        given(port.findById(1L)).willReturn(Optional.of(look(1L)));
        given(port.findItemsByLookIds(List.of(1L))).willReturn(List.of(new LookItem(11L, 1L, 200L, 0), new LookItem(12L, 1L, 100L, 1)));
        given(productListProcessor.summarizeVisible(anyCollection())).willReturn(Map.of(100L, summary(100L)));

        LookDetailResponse response = facade(true).getLook(1L);

        assertThat(response.lookId()).isEqualTo("1");
        assertThat(response.products()).singleElement().satisfies(product -> assertThat(product.productId()).isEqualTo("100"));
    }

    @Test
    @DisplayName("상품별 룩 — 없거나 숨긴 상품은 플래그와 무관하게 먼저 404 PRODUCT_001 이고 룩을 읽지 않는다")
    void hiddenProductFirst() {
        given(productDetailProcessor.getVisibleProduct(9L)).willThrow(new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));

        for (boolean flag : List.of(true, false)) {
            assertThatThrownBy(() -> facade(flag).getLooksOfProduct(9L))
                .isInstanceOf(ProductException.class)
                .satisfies(exception -> assertThat(((ProductException) exception).getErrorCode()).isEqualTo(ProductErrorCode.NOT_FOUND_PRODUCT));
        }
        verify(port, never()).findVisibleByProductId(anyLong());
    }

    @Test
    @DisplayName("상품별 룩 — 상품 확인 → 룩 조회 순서이고, 플래그가 꺼지면 상품만 확인하고 빈 looks 다")
    void looksOfProduct() {
        given(productDetailProcessor.getVisibleProduct(700L)).willReturn(ProductFixtures.product(700L));
        given(port.findVisibleByProductId(700L)).willReturn(List.of(look(3L)));
        given(port.findItemsByLookIds(anyCollection())).willReturn(List.of(new LookItem(31L, 3L, 700L, 0)));
        given(productListProcessor.summarizeVisible(anyCollection())).willReturn(Map.of(700L, summary(700L)));

        ProductLookListResponse on = facade(true).getLooksOfProduct(700L);
        ProductLookListResponse off = facade(false).getLooksOfProduct(700L);

        assertThat(on.looks()).singleElement().satisfies(look -> assertThat(look.lookId()).isEqualTo("3"));
        assertThat(off.looks()).isEmpty();
        InOrder order = inOrder(productDetailProcessor, port);
        order.verify(productDetailProcessor).getVisibleProduct(700L);
        order.verify(port).findVisibleByProductId(700L);
        verify(port, times(1)).findVisibleByProductId(700L);
    }

    private LookWebFacade facade(boolean looks) {
        LookWebProcessor processor = new LookWebProcessor(port, new StoreProperties(new StoreProperties.Features(false, false, false, looks)));
        return new LookWebFacade(processor, productListProcessor, productDetailProcessor, new LookWebPresenter());
    }

    private static Look look(long id) {
        return new Look(id, "룩" + id, null, IMAGE, true, 0);
    }

    private static ProductSummary summary(long productId) {
        return new ProductSummary(productId, "상품" + productId, null, null, Money.of(10_000L, KRW), null, null, false, SaleAvailability.ON_SALE, false, false);
    }
}
