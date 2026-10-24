package com.commercefoundry.domainlayer.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort;
import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.product.adapter.in.web.presenter.ProductWebPresenter;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductDetailProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductListProcessor;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.support.ProductFixtures;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 상품 상세 breadcrumb 이 메뉴 트리와 같은 숨김 규칙을 따르는지 (#164). 실제 {@link CategoryProcessor} 를 끼워 상세 Facade 가
 * {@link ProductDetailProcessor} 에 넘기는 경로를 본다 — 저장소만 mock 이다.
 */
@ExtendWith(MockitoExtension.class)
class ProductDetailBreadcrumbTest {

    //  상의(1) ─ 저고리(11) ─ 소매(111)
    //    └ 숨김상의(12, 숨김) ─ 숨김-하위(121) ─ 숨김-하위-소(1211)
    //  숨김루트(2, 숨김) ─ 숨김루트-하위(21)
    private static final List<Category> CATEGORIES = List.of(
        new Category(1, null, "상의", 0, true),
        new Category(11, 1L, "저고리", 0, true),
        new Category(111, 11L, "소매", 0, true),
        new Category(12, 1L, "숨김상의", 1, false),
        new Category(121, 12L, "숨김-하위", 0, true),
        new Category(1211, 121L, "숨김-하위-소", 0, true),
        new Category(2, null, "숨김루트", 1, false),
        new Category(21, 2L, "숨김루트-하위", 0, true)
    );

    @Mock
    private CategoryRepositoryPort categoryRepositoryPort;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private ProductListProcessor productListProcessor;

    @Mock
    private ProductDetailProcessor productDetailProcessor;

    @Mock
    private ProductWebPresenter productWebPresenter;

    private ProductWebFacade facade;

    @BeforeEach
    void setUp() {
        CategoryProcessor categoryProcessor = new CategoryProcessor(categoryRepositoryPort, snowflakeIdGenerator);
        facade = new ProductWebFacade(categoryProcessor, productListProcessor, productDetailProcessor, productWebPresenter);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "전부 보임 — 최상위부터 대표 카테고리까지                       | 111  | 1;11;111",
        "숨긴 상위 바로 아래 하위 — 숨긴 상위에서 끊겨 하위 이름이 나오지 않는다 | 121  | 1",
        "숨긴 중간 노드 아래 깊은 하위 — 숨긴 노드 위까지만                  | 1211 | 1",
        "대표 카테고리 자체가 숨김 — 그 위까지만                          | 12   | 1",
        "최상위가 숨김 — 빈 breadcrumb                                    | 21   | ''"
    })
    @DisplayName("상품 상세 breadcrumb 은 메뉴에서 닿는 경로만 넘긴다")
    void breadcrumbFollowsMenuRule(String scenario, long categoryId, String expectedIds) {
        Product product = ProductFixtures.product(7L, categoryId);
        given(productDetailProcessor.getVisibleProduct(7L)).willReturn(product);
        given(categoryRepositoryPort.findAll()).willReturn(CATEGORIES);

        facade.getProduct(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Category>> path = ArgumentCaptor.forClass(List.class);
        verify(productDetailProcessor).getDetail(eq(product), path.capture());
        List<Long> expected = expectedIds.isEmpty() ? List.of() : List.of(expectedIds.split(";")).stream().map(Long::valueOf).toList();
        assertThat(path.getValue()).extracting(Category::id).as(scenario).containsExactlyElementsOf(expected);
    }
}
