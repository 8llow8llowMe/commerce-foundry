package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCategoryEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCustomOrderFieldEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductImageEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.StockEntity;
import com.commercefoundry.domainlayer.product.application.mapper.ProductCustomOrderFieldMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.ProductImageMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.ProductMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.ProductOptionMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.SkuMapperImpl;
import com.commercefoundry.domainlayer.product.application.mapper.StockMapperImpl;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductAdminListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.global.config.ProductServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 상품 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 — QueryDSL · 파생 쿼리는 컴파일로 검증되지 않는다 ({@code coding-conventions.md} §9-5).
 *
 * <p>스키마는 DDL 스냅숏을 init script 로 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다. 컨텍스트가 뜨면 스냅숏과 매핑이 맞는 것이다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=product",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({
    QuerydslConfigurer.class, ProductServiceFeaturesConfig.class,
    ProductRepositoryAdapter.class, ProductOptionRepositoryAdapter.class, SkuRepositoryAdapter.class, StockRepositoryAdapter.class,
    ProductImageRepositoryAdapter.class, ProductCustomOrderFieldRepositoryAdapter.class,
    ProductMapperImpl.class, ProductOptionMapperImpl.class, SkuMapperImpl.class, StockMapperImpl.class,
    ProductImageMapperImpl.class, ProductCustomOrderFieldMapperImpl.class
})
class ProductPersistenceAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/product-schema.sql");

    // 고객에게 보이는 상품은 101 · 102 · 103 · 105 다. 104 는 숨김이다.
    //   상품  판매가   노출  판매 상태      진열 카테고리
    //   101  10,000   O    ON_SALE       10
    //   102  20,000   O    STOP_SELLING  10, 11   ← 두 카테고리에 진열
    //   103  10,000   O    ON_SALE       11       ← 101 과 같은 가격 (동률은 아이디 내림차순)
    //   104   5,000   X    ON_SALE       10
    //   105  30,000   O    ON_SALE       12
    private static final String LONG_DESCRIPTION = "<p>" + "가".repeat(5_000) + "</p>";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductRepositoryPort productRepositoryPort;

    @Autowired
    private ProductOptionRepositoryPort productOptionRepositoryPort;

    @Autowired
    private SkuRepositoryPort skuRepositoryPort;

    @Autowired
    private StockRepositoryPort stockRepositoryPort;

    @Autowired
    private ProductImageRepositoryPort productImageRepositoryPort;

    @Autowired
    private ProductCustomOrderFieldRepositoryPort productCustomOrderFieldRepositoryPort;

    @BeforeEach
    void setUp() {
        persistAll(
            product(101L, 10_000L, true, SaleStatus.ON_SALE).build(),
            product(102L, 20_000L, true, SaleStatus.STOP_SELLING)
                .listPriceAmount(25_000L).listPriceCurrency(KRW)
                .descriptionHtml(LONG_DESCRIPTION)
                .customOrder(true).customOrderNotice("치수를 입력하면 맞춰 제작합니다.").customOrderLeadTimeDays(14)
                .build(),
            product(103L, 10_000L, true, SaleStatus.ON_SALE).build(),
            product(104L, 5_000L, false, SaleStatus.ON_SALE).build(),
            product(105L, 30_000L, true, SaleStatus.ON_SALE).build(),

            displayedIn(9001L, 101L, 10L),
            displayedIn(9002L, 102L, 10L),
            displayedIn(9003L, 102L, 11L),
            displayedIn(9004L, 103L, 11L),
            displayedIn(9005L, 104L, 10L),
            displayedIn(9006L, 105L, 12L),

            // 101: 판매중 SKU 추가금 0 하나 + 판매 종료 SKU 추가금 5,000 (집계에서 빠진다)
            // 102: 판매 종료 SKU 하나뿐   103: 판매중 SKU 추가금 0 · 1,000   104: 판매중 SKU 하나   105: SKU 없음
            sku(1001L, 101L, "2001", 0L, SaleStatus.ON_SALE),
            sku(1002L, 101L, "2002", 5_000L, SaleStatus.STOP_SELLING),
            sku(1003L, 102L, "", 3_000L, SaleStatus.STOP_SELLING),
            sku(1004L, 103L, "2003", 0L, SaleStatus.ON_SALE),
            sku(1005L, 103L, "2004", 1_000L, SaleStatus.ON_SALE),
            sku(1006L, 104L, "", 0L, SaleStatus.ON_SALE),

            // 재고 — 1001 수량 3 · 1002 수량 10(판매 종료라 구매 가능에 들지 않는다) · 1003 행 없음
            //        1004 수량 0 · 1005 행 없음 (103 은 판매중 SKU 가 모두 품절) · 1006 무제한(수량 0)
            stock(1001L, true, 3L),
            stock(1002L, true, 10L),
            stock(1004L, true, 0L),
            stock(1006L, false, 0L),

            optionGroup(3001L, 101L, "색상", 1),
            optionGroup(3002L, 101L, "사이즈", 0),
            optionGroup(3003L, 103L, "사이즈", 0),
            optionValue(2001L, 3001L, 101L, "빨강", 1),
            optionValue(2002L, 3001L, 101L, "파랑", 0),
            optionValue(2005L, 3002L, 101L, "M", 0),
            optionValue(2003L, 3003L, 103L, "S", 0),
            optionValue(2004L, 3003L, 103L, "M", 1),

            image(4001L, 101L, ImageType.THUMBNAIL, 0),
            image(4002L, 101L, ImageType.GALLERY, 1),
            image(4003L, 101L, ImageType.GALLERY, 0),
            image(4004L, 103L, ImageType.THUMBNAIL, 0),

            customOrderField(5001L, 102L, "chest", CustomOrderInputType.NUMBER, true,
                new BigDecimal("50.00"), new BigDecimal("150.00"), new BigDecimal("0.50"), 1),
            customOrderField(5002L, 102L, "memo", CustomOrderInputType.TEXT, false, null, new BigDecimal("200"), null, 0)
        );
    }

    @Test
    @DisplayName("스키마: 스냅숏 DDL 위에서 validate 로 컨텍스트가 뜨고, 감사 시각과 긴 상세 설명(text)이 저장된다")
    void schema() {
        ProductEntity saved = entityManager.find(ProductEntity.class, 102L);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDescriptionHtml()).isEqualTo(LONG_DESCRIPTION);
    }

    @Test
    @DisplayName("목록: 숨김 상품은 빠지고 판매 종료 상품은 남는다 — 최신순은 아이디 내림차순이다")
    void searchNewest() {
        Slice<ProductListQueryResult> slice = productRepositoryPort.searchVisible(criteria(null, ProductSortType.NEWEST, 0, 20));

        assertThat(slice.getContent()).extracting(ProductListQueryResult::productId).containsExactly(105L, 103L, 102L, 101L);
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    @DisplayName("아이디 묶음 목록 행(전시): 숨김(104) · 없는 아이디는 빠지고 아이디 오름차순이며, 목록과 같은 칸을 읽는다")
    void findVisibleListRowsByIds() {
        List<ProductListQueryResult> rows = productRepositoryPort.findVisibleListRowsByIds(List.of(105L, 104L, 102L, 999L));

        assertThat(rows).extracting(ProductListQueryResult::productId).containsExactly(102L, 105L);
        ProductListQueryResult discounted = rows.getFirst();
        assertThat(discounted.saleStatus()).isEqualTo(SaleStatus.STOP_SELLING);
        assertThat(discounted.listPriceAmount()).isEqualTo(25_000L);
        assertThat(discounted.customOrder()).isTrue();
        assertThat(productRepositoryPort.findVisibleListRowsByIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("목록: 가격순은 같은 가격을 아이디 내림차순으로 끊는다")
    void searchByPrice() {
        assertThat(productRepositoryPort.searchVisible(criteria(null, ProductSortType.PRICE_ASC, 0, 20)).getContent())
            .extracting(ProductListQueryResult::productId)
            .containsExactly(103L, 101L, 102L, 105L);
        assertThat(productRepositoryPort.searchVisible(criteria(null, ProductSortType.PRICE_DESC, 0, 20)).getContent())
            .extracting(ProductListQueryResult::productId)
            .containsExactly(105L, 102L, 103L, 101L);
    }

    @Test
    @DisplayName("목록: 카테고리 필터는 고른 카테고리 중 한 곳에라도 진열된 상품이고, 여러 곳에 진열돼도 한 번만 나온다")
    void searchByCategories() {
        assertThat(productRepositoryPort.searchVisible(criteria(Set.of(10L, 11L), ProductSortType.NEWEST, 0, 20)).getContent())
            .extracting(ProductListQueryResult::productId)
            .containsExactly(103L, 102L, 101L);
        assertThat(productRepositoryPort.searchVisible(criteria(Set.of(10L), ProductSortType.PRICE_ASC, 0, 20)).getContent())
            .extracting(ProductListQueryResult::productId)
            .containsExactly(101L, 102L);
        assertThat(productRepositoryPort.searchVisible(criteria(Set.of(12L), ProductSortType.NEWEST, 0, 20)).getContent())
            .extracting(ProductListQueryResult::productId)
            .containsExactly(105L);
        assertThat(productRepositoryPort.searchVisible(criteria(Set.of(99L), ProductSortType.NEWEST, 0, 20)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("목록: 빈 카테고리 집합이면 빈 Slice 다")
    void searchWithEmptyCategories() {
        Slice<ProductListQueryResult> slice = productRepositoryPort.searchVisible(criteria(Set.of(), ProductSortType.NEWEST, 0, 20));

        assertThat(slice.getContent()).isEmpty();
        assertThat(slice.hasNext()).isFalse();
    }

    @Test
    @DisplayName("목록: size + 1 건을 읽어 다음 페이지 유무를 정한다")
    void slicing() {
        Slice<ProductListQueryResult> first = productRepositoryPort.searchVisible(criteria(null, ProductSortType.NEWEST, 0, 2));
        Slice<ProductListQueryResult> second = productRepositoryPort.searchVisible(criteria(null, ProductSortType.NEWEST, 1, 2));
        Slice<ProductListQueryResult> beyond = productRepositoryPort.searchVisible(criteria(null, ProductSortType.NEWEST, 2, 2));

        assertThat(first.getContent()).extracting(ProductListQueryResult::productId).containsExactly(105L, 103L);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.getContent()).extracting(ProductListQueryResult::productId).containsExactly(102L, 101L);
        assertThat(second.hasNext()).isFalse();
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
        assertThat(productRepositoryPort.searchVisible(criteria(null, ProductSortType.NEWEST, 0, 3)).hasNext()).isTrue();
        assertThat(productRepositoryPort.searchVisible(criteria(null, ProductSortType.NEWEST, 0, 4)).hasNext()).isFalse();
    }

    @Test
    @DisplayName("목록: 행에 가격 · 정가 · 판매 상태 · 맞춤 제작 여부를 담는다")
    void listRow() {
        List<ProductListQueryResult> rows = productRepositoryPort.searchVisible(criteria(Set.of(11L), ProductSortType.NEWEST, 0, 20)).getContent();

        assertThat(rows).containsExactly(
            new ProductListQueryResult(103L, "상품 103", 10_000L, KRW, null, null, SaleStatus.ON_SALE, false),
            new ProductListQueryResult(102L, "상품 102", 20_000L, KRW, 25_000L, KRW, SaleStatus.STOP_SELLING, true)
        );
    }

    @Test
    @DisplayName("상세: 보이는 상품만 찾는다 — 숨김 · 없는 상품은 비어 있다")
    void findVisibleById() {
        assertThat(productRepositoryPort.findVisibleById(102L)).hasValueSatisfying(product -> {
            assertThat(product.salePrice()).isEqualTo(Money.of(20_000, KRW));
            assertThat(product.listPrice()).isEqualTo(Money.of(25_000, KRW));
            assertThat(product.saleStatus()).isEqualTo(SaleStatus.STOP_SELLING);
            assertThat(product.customOrder()).isTrue();
            assertThat(product.customOrderLeadTimeDays()).isEqualTo(14);
            assertThat(product.descriptionHtml()).isEqualTo(LONG_DESCRIPTION);
        });
        assertThat(productRepositoryPort.findVisibleById(104L)).isEmpty();
        assertThat(productRepositoryPort.findVisibleById(999L)).isEmpty();
    }

    @Test
    @DisplayName("internal · 어드민: 아이디 목록 조회는 숨김 상품까지 읽는다")
    void findAllByIdsIncludingHidden() {
        assertThat(productRepositoryPort.findAllByIdsIncludingHidden(List.of(101L, 104L, 999L)))
            .extracting(Product::id)
            .containsExactlyInAnyOrder(101L, 104L);
    }

    @Test
    @DisplayName("SKU 집계: 상품별 판매중 · 구매 가능 SKU 유무와 판매중 SKU 추가금 최소 · 최대를 한 번에 내고, 판매 종료 SKU 추가금 · 재고는 무시한다 — SKU 수는 판매 상태와 무관하게 센다")
    void aggregateSkus() {
        List<SkuAggregateQueryResult> aggregates = skuRepositoryPort.aggregateByProductIds(List.of(101L, 102L, 103L, 104L, 105L));

        assertThat(aggregates).containsExactlyInAnyOrder(
            // 최소 = 최대 — 판매 종료 SKU(1002)의 추가금 5,000 은 섞이지 않는다. 1001 은 수량 3 이라 구매 가능하다
            new SkuAggregateQueryResult(101L, true, true, 0L, 0L, 2),
            // 판매중 SKU 가 없으면 최소 · 최대가 null 이고 구매 가능 SKU 도 없다
            new SkuAggregateQueryResult(102L, false, false, null, null, 1),
            // 최소 ≠ 최대 — 옵션에 따라 가격이 다르다. 수량 0(1004) · 재고 행 없음(1005)은 품절이라 구매 가능 SKU 가 없다
            new SkuAggregateQueryResult(103L, true, false, 0L, 1_000L, 2),
            // 무제한 SKU 는 수량 0 이어도 구매 가능하다
            new SkuAggregateQueryResult(104L, true, true, 0L, 0L, 1)
        );
        assertThat(aggregates).extracting(SkuAggregateQueryResult::productId).as("SKU 없는 상품은 행이 없다").doesNotContain(105L);
    }

    @Test
    @DisplayName("SKU 집계: 판매중 SKU 하나라도 수량이 있으면 구매 가능하고, 재고 left join 이 SKU 행을 늘리지 않는다")
    void aggregateWithPartialStock() {
        persistAll(stock(1005L, true, 2L));

        assertThat(skuRepositoryPort.aggregateByProductIds(List.of(103L)))
            .containsExactly(new SkuAggregateQueryResult(103L, true, true, 0L, 1_000L, 2));
    }

    // 어드민 목록 — 판매 가능 상태: 101 판매중 · 102 판매 종료(상품) · 103 품절(수량 0 · 재고 행 없음) · 104 판매중(숨김, 무제한) · 105 판매 종료(SKU 없음)

    @Test
    @DisplayName("어드민 목록: 조건이 없으면 숨김 상품까지 전체이고 최신순이며, 행에 저장된 가격 · 판매 상태 · 노출 · 수정 시각을 담는다")
    void adminSearchAll() {
        Page<ProductAdminListQueryResult> page = productRepositoryPort.searchForAdmin(adminCriteria().build());

        assertThat(page.getContent()).extracting(ProductAdminListQueryResult::productId).containsExactly(105L, 104L, 103L, 102L, 101L);
        assertThat(page.getTotalElements()).isEqualTo(5);
        ProductAdminListQueryResult row = page.getContent().get(3);
        assertThat(row.name()).isEqualTo("상품 102");
        assertThat(row.salePrice()).isEqualTo(Money.of(20_000, KRW));
        assertThat(row.listPrice()).isEqualTo(Money.of(25_000, KRW));
        assertThat(row.saleStatus()).isEqualTo(SaleStatus.STOP_SELLING);
        assertThat(row.visible()).isTrue();
        assertThat(row.updatedAt()).isNotNull();
        assertThat(page.getContent().get(1).visible()).isFalse();
    }

    @Test
    @DisplayName("어드민 목록: 노출 여부 · 저장된 판매 상태로 거른다")
    void adminSearchByVisibleAndSaleStatus() {
        assertThat(adminIds(adminCriteria().visible(false))).containsExactly(104L);
        assertThat(adminIds(adminCriteria().visible(true))).containsExactly(105L, 103L, 102L, 101L);
        assertThat(adminIds(adminCriteria().saleStatus(SaleStatus.STOP_SELLING))).containsExactly(102L);
        assertThat(adminIds(adminCriteria().saleStatus(SaleStatus.ON_SALE))).containsExactly(105L, 104L, 103L, 101L);
    }

    @Test
    @DisplayName("어드민 목록: 판매 가능 상태는 고객 뱃지와 같은 규칙이다 — 재고 행 없음 · 수량 0 은 품절, 무제한은 판매중, 상품 판매 종료 · SKU 없음은 판매 종료")
    void adminSearchByAvailability() {
        assertThat(adminIds(adminCriteria().availability(SaleAvailability.ON_SALE))).containsExactly(104L, 101L);
        assertThat(adminIds(adminCriteria().availability(SaleAvailability.SOLD_OUT))).containsExactly(103L);
        assertThat(adminIds(adminCriteria().availability(SaleAvailability.STOP_SELLING))).containsExactly(105L, 102L);
    }

    @Test
    @DisplayName("어드민 목록: 판매중 SKU 하나에 재고가 생기면 품절에서 판매중으로 옮겨 가고, 판매 종료 SKU 의 재고는 판매 가능 상태에 들지 않는다")
    void adminAvailabilityFollowsStock() {
        persistAll(
            stock(1005L, true, 2L),
            product(106L, 1_000L, true, SaleStatus.ON_SALE).build(),
            sku(1007L, 106L, "", 0L, SaleStatus.STOP_SELLING),
            sku(1008L, 106L, "9001", 0L, SaleStatus.ON_SALE),
            stock(1007L, true, 5L)
        );

        assertThat(adminIds(adminCriteria().availability(SaleAvailability.ON_SALE))).containsExactly(104L, 103L, 101L);
        assertThat(adminIds(adminCriteria().availability(SaleAvailability.SOLD_OUT))).containsExactly(106L);
    }

    @Test
    @DisplayName("어드민 목록: 상품명 부분 일치는 대소문자를 무시하고, 검색어의 % · _ · ! 는 와일드카드가 아니라 글자다")
    void adminSearchByKeyword() {
        persistAll(
            product(106L, 1_000L, true, SaleStatus.ON_SALE).name("MOKO 저고리 100%").build(),
            product(107L, 1_000L, true, SaleStatus.ON_SALE).name("mokoyuri 바지").build()
        );

        assertThat(adminIds(adminCriteria().keyword("103"))).containsExactly(103L);
        assertThat(adminIds(adminCriteria().keyword("상품 10"))).containsExactly(105L, 104L, 103L, 102L, 101L);
        // 양방향 — 소문자 검색어로 대문자 이름을, 대문자 검색어로 소문자 이름을 찾는다
        assertThat(adminIds(adminCriteria().keyword("moko"))).containsExactly(107L, 106L);
        assertThat(adminIds(adminCriteria().keyword("MOKO"))).containsExactly(107L, 106L);
        assertThat(adminIds(adminCriteria().keyword("100%"))).containsExactly(106L);
        assertThat(adminIds(adminCriteria().keyword("%"))).containsExactly(106L);
        assertThat(adminIds(adminCriteria().keyword("_"))).isEmpty();
        assertThat(adminIds(adminCriteria().keyword("!"))).isEmpty();
    }

    @Test
    @DisplayName("어드민 목록: 카테고리는 고른 집합 중 한 곳에라도 진열된 상품이고 여러 곳에 진열돼도 한 번만 세며, 조건을 함께 걸면 모두 만족해야 한다")
    void adminSearchByCategoriesAndCombination() {
        Page<ProductAdminListQueryResult> page = productRepositoryPort.searchForAdmin(adminCriteria().categoryIds(Set.of(11L, 12L)).build());

        assertThat(page.getContent()).extracting(ProductAdminListQueryResult::productId).containsExactly(105L, 103L, 102L);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(adminIds(adminCriteria().categoryIds(Set.of(10L)))).containsExactly(104L, 102L, 101L);
        assertThat(adminIds(adminCriteria().categoryIds(Set.of(10L)).visible(true).availability(SaleAvailability.ON_SALE))).containsExactly(101L);
        assertThat(adminIds(adminCriteria().categoryIds(Set.of(10L)).keyword("104").saleStatus(SaleStatus.ON_SALE).visible(false))).containsExactly(104L);
        assertThat(adminIds(adminCriteria().categoryIds(Set.of(12L)).availability(SaleAvailability.ON_SALE))).isEmpty();
        assertThat(adminIds(adminCriteria().categoryIds(Set.of(10L)).availability(SaleAvailability.STOP_SELLING))).as("판매 종료 조건의 or 가 다른 조건 밖으로 새지 않는다")
            .containsExactly(102L);
    }

    @Test
    @DisplayName("어드민 목록: 이름순은 같은 이름을 아이디 내림차순으로 끊는다")
    void adminSortByName() {
        persistAll(
            product(106L, 1_000L, true, SaleStatus.ON_SALE).name("가방").build(),
            product(107L, 1_000L, false, SaleStatus.ON_SALE).name("가방").build()
        );

        assertThat(adminIds(adminCriteria().sortType(ProductAdminSortType.NAME_ASC))).containsExactly(107L, 106L, 101L, 102L, 103L, 104L, 105L);
    }

    @Test
    @DisplayName("어드민 목록: 페이지를 자르고 전체 건수는 자르기 전 개수다 — 마지막 페이지를 넘기면 빈 목록이고 전체 건수는 그대로다")
    void adminPaging() {
        Page<ProductAdminListQueryResult> second = productRepositoryPort.searchForAdmin(adminCriteria().page(1).size(2).build());

        assertThat(second.getContent()).extracting(ProductAdminListQueryResult::productId).containsExactly(103L, 102L);
        assertThat(second.getTotalElements()).isEqualTo(5);
        assertThat(second.getTotalPages()).isEqualTo(3);

        Page<ProductAdminListQueryResult> beyond = productRepositoryPort.searchForAdmin(adminCriteria().page(5).size(2).build());
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(5);

        Page<ProductAdminListQueryResult> filtered = productRepositoryPort.searchForAdmin(adminCriteria().visible(true).size(3).build());
        assertThat(filtered.getContent()).extracting(ProductAdminListQueryResult::productId).containsExactly(105L, 103L, 102L);
        assertThat(filtered.getTotalElements()).as("count 도 같은 조건이다").isEqualTo(4);
    }

    @Test
    @DisplayName("재고: SKU 아이디 목록으로 읽고, 재고 행이 없는 SKU 는 결과에 없다")
    void findStocks() {
        assertThat(stockRepositoryPort.findAllBySkuIds(List.of(1001L, 1004L, 1005L, 1006L)))
            .containsExactlyInAnyOrder(new Stock(1001L, true, 3L), new Stock(1004L, true, 0L), new Stock(1006L, false, 0L));
        assertThat(stockRepositoryPort.findAllBySkuIds(List.of(1003L, 9999L))).isEmpty();
    }

    @Test
    @DisplayName("재고: 감사 시각이 저장된다")
    void stockAudit() {
        StockEntity saved = entityManager.find(StockEntity.class, 1001L);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("제약: 음수 재고 수량은 저장되지 않는다 (ck_stock_available_quantity_non_negative)")
    void negativeStockIsRejected() {
        assertThatThrownBy(() -> persistAll(stock(1005L, true, -1L)))
            .rootCause()
            .hasMessageContaining("ck_stock_available_quantity_non_negative");
    }

    @Test
    @DisplayName("제약: 같은 상품에 같은 옵션 조합 키의 SKU 는 저장되지 않는다 (uk_sku_product_id_option_value_key)")
    void duplicateOptionKeyIsRejected() {
        assertThatThrownBy(() -> persistAll(sku(1009L, 101L, "2001", 0L, SaleStatus.ON_SALE)))
            .rootCause()
            .hasMessageContaining("uk_sku_product_id_option_value_key");
    }

    @Test
    @DisplayName("제약: 음수 추가금은 저장되지 않는다 (ck_sku_additional_price_non_negative)")
    void negativeAdditionalPriceIsRejected() {
        assertThatThrownBy(() -> persistAll(sku(1009L, 101L, "2005", -1L, SaleStatus.ON_SALE)))
            .rootCause()
            .hasMessageContaining("ck_sku_additional_price_non_negative");
    }

    @Test
    @DisplayName("제약: 무게 0g 은 저장되지 않는다 (ck_sku_weight_grams_positive)")
    void nonPositiveWeightIsRejected() {
        assertThatThrownBy(() -> persistAll(skuBuilder(1009L, 101L, "2005", 0L, SaleStatus.ON_SALE).weightGrams(0).build()))
            .rootCause()
            .hasMessageContaining("ck_sku_weight_grams_positive");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"62114", "6211.42.00", "6211-42", "621142 ", " 621142", "62114A", "６２１１４２"})
    @DisplayName("제약: 숫자 6~10자리가 아닌 HS 코드(5자리 · 점 · 하이픈 · 앞뒤 공백 · 문자 · 전각 숫자)는 저장되지 않는다 (ck_sku_hs_code_format)")
    void malformedHsCodeIsRejected(String hsCode) {
        assertThatThrownBy(() -> persistAll(skuBuilder(1009L, 101L, "2005", 0L, SaleStatus.ON_SALE).hsCode(hsCode).build()))
            .rootCause()
            .hasMessageContaining("ck_sku_hs_code_format");
    }

    @Test
    @DisplayName("제약: 10자리 초과 HS 코드는 저장되지 않는다 (varchar(10))")
    void longHsCodeIsRejected() {
        assertThatThrownBy(() -> persistAll(skuBuilder(1009L, 101L, "2005", 0L, SaleStatus.ON_SALE).hsCode("62114200001").build()))
            .rootCause()
            .hasMessageContaining("character varying(10)");
    }

    @Test
    @DisplayName("SKU: 무게 · HS 코드를 저장하고 읽는다 — 미입력은 null 그대로다")
    void skuWeightAndHsCode() {
        persistAll(skuBuilder(1009L, 101L, "2005", 0L, SaleStatus.ON_SALE).weightGrams(450).hsCode("0621142000").build());

        assertThat(skuRepositoryPort.findAllByIds(List.of(1009L, 1001L)))
            .extracting(Sku::id, Sku::weightGrams, sku -> sku.hsCode() == null ? null : sku.hsCode().value())
            .containsExactlyInAnyOrder(tuple(1009L, 450, "0621142000"), tuple(1001L, null, null));
    }

    @Test
    @DisplayName("제약: 경계값(무게 1g · HS 코드 6자리)은 저장된다")
    void boundaryWeightAndHsCodeAreAccepted() {
        persistAll(skuBuilder(1009L, 101L, "2005", 0L, SaleStatus.ON_SALE).weightGrams(1).hsCode("621142").build());

        assertThat(skuRepositoryPort.findAllByIds(List.of(1009L)))
            .extracting(Sku::weightGrams, sku -> sku.hsCode().value())
            .containsExactly(tuple(1, "621142"));
    }

    @Test
    @DisplayName("제약: 정가 금액만 있고 통화가 없으면 저장되지 않는다 (ck_product_list_price_pair)")
    void halfListPriceIsRejected() {
        assertThatThrownBy(() -> persistAll(product(106L, 10_000L, true, SaleStatus.ON_SALE).listPriceAmount(12_000L).build()))
            .rootCause()
            .hasMessageContaining("ck_product_list_price_pair");
    }

    @Test
    @DisplayName("SKU: 상품의 SKU 는 아이디 오름차순이고, 아이디 목록 조회는 해당 SKU 만 읽는다")
    void findSkus() {
        assertThat(skuRepositoryPort.findAllByProductId(101L))
            .extracting(Sku::id, sku -> sku.optionKey().value(), Sku::saleStatus)
            .containsExactly(tuple(1001L, "2001", SaleStatus.ON_SALE), tuple(1002L, "2002", SaleStatus.STOP_SELLING));
        assertThat(skuRepositoryPort.findAllByProductId(102L)).singleElement()
            .satisfies(sku -> assertThat(sku.optionKey().isEmpty()).isTrue());
        assertThat(skuRepositoryPort.findAllByIds(List.of(1001L, 1005L, 9999L)))
            .extracting(Sku::id, Sku::additionalPrice)
            .containsExactlyInAnyOrder(tuple(1001L, Money.zero(KRW)), tuple(1005L, Money.of(1_000, KRW)));
    }

    @Test
    @DisplayName("옵션: 상품의 그룹 · 값은 노출 순서 → 아이디 오름차순이다")
    void findOptionsByProduct() {
        assertThat(productOptionRepositoryPort.findGroupsByProductId(101L)).extracting(OptionGroup::id).containsExactly(3002L, 3001L);
        assertThat(productOptionRepositoryPort.findValuesByProductId(101L))
            .extracting(OptionValue::id, OptionValue::optionGroupId)
            .containsExactly(tuple(2002L, 3001L), tuple(2005L, 3002L), tuple(2001L, 3001L));
    }

    @Test
    @DisplayName("옵션: 아이디 목록으로 그룹 · 값을 읽는다")
    void findOptionsByIds() {
        assertThat(productOptionRepositoryPort.findGroupsByIds(List.of(3001L, 3003L, 9999L)))
            .extracting(OptionGroup::id, OptionGroup::productId, OptionGroup::name)
            .containsExactlyInAnyOrder(tuple(3001L, 101L, "색상"), tuple(3003L, 103L, "사이즈"));
        assertThat(productOptionRepositoryPort.findValuesByIds(List.of(2001L, 2004L)))
            .extracting(OptionValue::id, OptionValue::optionGroupId, OptionValue::productId, OptionValue::name)
            .containsExactlyInAnyOrder(tuple(2001L, 3001L, 101L, "빨강"), tuple(2004L, 3003L, 103L, "M"));
    }

    @Test
    @DisplayName("이미지: 상품의 이미지는 노출 순서 → 아이디 오름차순이고, 대표 이미지는 상품 목록으로 한 번에 읽는다")
    void findImages() {
        assertThat(productImageRepositoryPort.findAllByProductId(101L))
            .extracting(ProductImage::id, ProductImage::imageType)
            .containsExactly(tuple(4001L, ImageType.THUMBNAIL), tuple(4003L, ImageType.GALLERY), tuple(4002L, ImageType.GALLERY));
        assertThat(productImageRepositoryPort.findThumbnailsByProductIds(List.of(101L, 103L, 105L)))
            .extracting(ProductImage::id, ProductImage::productId, ProductImage::width, ProductImage::altText)
            .containsExactlyInAnyOrder(tuple(4001L, 101L, 800, "상품 101 이미지"), tuple(4004L, 103L, 800, "상품 103 이미지"));
    }

    @Test
    @DisplayName("맞춤 제작: 입력 항목은 노출 순서 → 아이디 오름차순이고 numeric(10,2) 범위 값을 그대로 읽는다")
    void findCustomOrderFields() {
        List<CustomOrderField> fields = productCustomOrderFieldRepositoryPort.findAllByProductId(102L);

        assertThat(fields).extracting(CustomOrderField::fieldKey).containsExactly("memo", "chest");
        CustomOrderField chest = fields.get(1);
        assertThat(chest.inputType()).isEqualTo(CustomOrderInputType.NUMBER);
        assertThat(chest.required()).isTrue();
        assertThat(chest.minValue()).isEqualByComparingTo("50");
        assertThat(chest.maxValue()).isEqualByComparingTo("150");
        assertThat(chest.stepValue()).isEqualByComparingTo("0.5");
        assertThat(fields.get(0).minValue()).isNull();
        assertThat(productCustomOrderFieldRepositoryPort.findAllByProductId(101L)).isEmpty();
    }

    private void persistAll(Object... entities) {
        for (Object entity : entities) {
            entityManager.persist(entity);
        }
        entityManager.flush();
        entityManager.clear();
    }

    private static ProductSearchCriteria criteria(Set<Long> categoryIds, ProductSortType sortType, int page, int size) {
        return new ProductSearchCriteria(categoryIds, sortType, page, size);
    }

    /** 어드민 목록 조건 — 기본은 조건 없음 · 최신순 · 0페이지 · 20건. */
    private static ProductAdminSearchCriteria.ProductAdminSearchCriteriaBuilder adminCriteria() {
        return ProductAdminSearchCriteria.builder().sortType(ProductAdminSortType.NEWEST).page(0).size(20);
    }

    private List<Long> adminIds(ProductAdminSearchCriteria.ProductAdminSearchCriteriaBuilder criteria) {
        return productRepositoryPort.searchForAdmin(criteria.build()).getContent().stream().map(ProductAdminListQueryResult::productId).toList();
    }

    private static ProductEntity.ProductEntityBuilder product(Long id, long salePriceAmount, boolean visible, SaleStatus saleStatus) {
        return ProductEntity.builder()
            .id(id)
            .categoryId(10L)
            .name("상품 " + id)
            .saleStatus(saleStatus)
            .visible(visible)
            .salePriceAmount(salePriceAmount)
            .salePriceCurrency(KRW);
    }

    private static ProductCategoryEntity displayedIn(Long id, Long productId, Long categoryId) {
        return ProductCategoryEntity.builder().id(id).productId(productId).categoryId(categoryId).displayOrder(0).build();
    }

    private static SkuEntity sku(Long id, Long productId, String optionValueKey, long additionalPriceAmount, SaleStatus saleStatus) {
        return skuBuilder(id, productId, optionValueKey, additionalPriceAmount, saleStatus).build();
    }

    private static SkuEntity.SkuEntityBuilder skuBuilder(
        Long id, Long productId, String optionValueKey, long additionalPriceAmount, SaleStatus saleStatus
    ) {
        return SkuEntity.builder()
            .id(id)
            .productId(productId)
            .optionValueKey(optionValueKey)
            .additionalPriceAmount(additionalPriceAmount)
            .additionalPriceCurrency(KRW)
            .saleStatus(saleStatus);
    }

    private static StockEntity stock(Long skuId, boolean stockManaged, long availableQuantity) {
        return StockEntity.builder().skuId(skuId).stockManaged(stockManaged).availableQuantity(availableQuantity).build();
    }

    private static ProductOptionGroupEntity optionGroup(Long id, Long productId, String name, int displayOrder) {
        return ProductOptionGroupEntity.builder().id(id).productId(productId).name(name).displayOrder(displayOrder).build();
    }

    private static ProductOptionValueEntity optionValue(Long id, Long optionGroupId, Long productId, String name, int displayOrder) {
        return ProductOptionValueEntity.builder()
            .id(id).optionGroupId(optionGroupId).productId(productId).name(name).displayOrder(displayOrder)
            .build();
    }

    private static ProductImageEntity image(Long id, Long productId, ImageType imageType, int displayOrder) {
        return ProductImageEntity.builder()
            .id(id)
            .productId(productId)
            .imageType(imageType)
            .url("https://cdn.example.com/products/%d/%d.jpg".formatted(productId, id))
            .width(800)
            .height(800)
            .altText("상품 %d 이미지".formatted(productId))
            .displayOrder(displayOrder)
            .build();
    }

    private static ProductCustomOrderFieldEntity customOrderField(
        Long id, Long productId, String fieldKey, CustomOrderInputType inputType, boolean required,
        BigDecimal minValue, BigDecimal maxValue, BigDecimal stepValue, int displayOrder
    ) {
        return ProductCustomOrderFieldEntity.builder()
            .id(id)
            .productId(productId)
            .fieldKey(fieldKey)
            .label(fieldKey + " 라벨")
            .inputType(inputType)
            .unit(inputType == CustomOrderInputType.NUMBER ? "cm" : null)
            .required(required)
            .minValue(minValue)
            .maxValue(maxValue)
            .stepValue(stepValue)
            .displayOrder(displayOrder)
            .build();
    }
}
