package com.commercefoundry.domainlayer.product.application.service;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.ProductAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminListItemInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminQueryProcessor;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 어드민 상품 목록 Facade — 카테고리를 하위까지 펼쳐 넘기는지, Presenter 가 페이지 정보 · 아이디 String · 금액 · enum 메타데이터로 바꾸는지 본다.
 * Presenter 는 실제 객체를 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class ProductAdminFacadeTest {

    private static final ProductAdminSearchCommand ALL = ProductAdminSearchCommand.builder().sortType(ProductAdminSortType.NEWEST).page(0).size(20).build();

    @Mock
    private CategoryProcessor categoryProcessor;

    @Mock
    private ProductAdminQueryProcessor productAdminQueryProcessor;

    private ProductAdminFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ProductAdminFacade(categoryProcessor, productAdminQueryProcessor, new ProductAdminPresenter(), org.mockito.Mockito.mock(
            com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminDetailProcessor.class));
    }

    @Test
    @DisplayName("카테고리 조건은 카테고리 컨텍스트가 펼친 하위 포함 집합으로 넘긴다")
    void expandsCategorySubtree() {
        ProductAdminSearchCommand command = ProductAdminSearchCommand.builder().categoryId(10L).sortType(ProductAdminSortType.NEWEST).page(0).size(20).build();
        given(categoryProcessor.subtreeIdsOf(10L)).willReturn(Set.of(10L, 11L, 111L));
        given(productAdminQueryProcessor.search(any(), any())).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        facade.searchProducts(command);

        verify(productAdminQueryProcessor).search(command, Set.of(10L, 11L, 111L));
    }

    @Test
    @DisplayName("카테고리 조건이 없으면 카테고리를 읽지 않고 null 로 넘긴다")
    void noCategory() {
        given(productAdminQueryProcessor.search(any(), isNull())).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        facade.searchProducts(ALL);

        verify(productAdminQueryProcessor).search(ALL, null);
        verifyNoInteractions(categoryProcessor);
    }

    @Test
    @DisplayName("응답은 페이지 정보(자르기 전 전체 건수)를 그대로 두고 항목을 아이디 String · {amount, currency} · {code, name, description} 으로 바꾼다")
    void presentsPage() {
        ProductAdminListItemInfo info = ProductAdminListItemInfo.builder()
            .productId(4611686018427387904L)
            .name("저고리")
            .thumbnailUrl("https://cdn.example.com/p.jpg")
            .salePrice(Money.of(39_000, KRW))
            .listPrice(null)
            .saleStatus(SaleStatus.STOP_SELLING)
            .visible(false)
            .availability(SaleAvailability.STOP_SELLING)
            .skuCount(2)
            .updatedAt(Instant.parse("2026-10-18T09:00:00Z"))
            .build();
        given(productAdminQueryProcessor.search(any(), isNull())).willReturn(new PageImpl<>(List.of(info), PageRequest.of(1, 1), 3));

        PageResponse<ProductListAdminItem> page = facade.searchProducts(ALL);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        ProductListAdminItem item = page.contents().getFirst();
        assertThat(item.productId()).isEqualTo("4611686018427387904");
        assertThat(item.thumbnailUrl()).isEqualTo("https://cdn.example.com/p.jpg");
        assertThat(item.salePrice().amount()).isEqualTo(39_000);
        assertThat(item.salePrice().currency()).isEqualTo("KRW");
        assertThat(item.listPrice()).isNull();
        assertThat(item.saleStatus().code()).isEqualTo("STOP_SELLING");
        assertThat(item.saleStatus().name()).isEqualTo("판매 종료");
        assertThat(item.availability().code()).isEqualTo("STOP_SELLING");
        assertThat(item.visible()).isFalse();
        assertThat(item.skuCount()).isEqualTo(2);
        assertThat(item.updatedAt()).isEqualTo(Instant.parse("2026-10-18T09:00:00Z"));
    }
}
