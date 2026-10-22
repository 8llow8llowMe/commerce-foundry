package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QProductCategoryEntity.productCategoryEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QProductEntity.productEntity;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QSkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QStockEntity;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSortType;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductAdminListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.support.PageableExecutionUtils;

@RequiredArgsConstructor
public class ProductCustomRepositoryImpl implements ProductCustomRepository {

    private final JPAQueryFactory queryFactory;

    /**
     * 전체 건수를 세지 않는 Slice 다 — {@code size + 1} 건을 읽어 다음 페이지 유무만 본다.
     *
     * <p>카테고리 필터는 {@code product_category} 에 EXISTS 로 건다. JOIN 하면 한 상품이 고른 카테고리 여러 곳에 진열됐을 때
     * 행이 겹쳐 같은 상품이 두 번 나오고 페이지 크기가 어긋난다.
     *
     * <p>빈 카테고리 집합 검사는 방어용이다 — "빈 집합이면 쿼리 없이 빈 Slice" 계약은 어댑터({@code ProductRepositoryAdapter})가 지킨다.
     */
    @Override
    public Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria) {
        PageRequest pageRequest = PageRequest.of(criteria.page(), criteria.size());
        Set<Long> categoryIds = criteria.categoryIds();
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new SliceImpl<>(List.of(), pageRequest, false);
        }

        List<ProductListQueryResult> rows = queryFactory
            .select(Projections.constructor(
                ProductListQueryResult.class,
                productEntity.id, productEntity.name, productEntity.salePriceAmount, productEntity.salePriceCurrency,
                productEntity.listPriceAmount, productEntity.listPriceCurrency, productEntity.saleStatus, productEntity.customOrder
            ))
            .from(productEntity)
            .where(visibleToCustomer(), displayedInAny(categoryIds))
            .orderBy(orderOf(criteria.sortType()))
            .offset(pageRequest.getOffset())
            .limit(criteria.size() + 1L)
            .fetch();

        boolean hasNext = rows.size() > criteria.size();
        List<ProductListQueryResult> contents = hasNext ? rows.subList(0, criteria.size()) : rows;
        return new SliceImpl<>(List.copyOf(contents), pageRequest, hasNext);
    }

    @Override
    public Optional<ProductEntity> findVisibleById(long productId) {
        return Optional.ofNullable(queryFactory
            .selectFrom(productEntity)
            .where(productEntity.id.eq(productId), visibleToCustomer())
            .fetchOne());
    }

    /**
     * 어드민 상품 목록 — 숨김 상품을 포함하고 삭제(soft delete) 상품은 늘 뺀다(삭제 상품을 보는 필터는 없다). 전체 건수를 센다.
     * 목록 1번 + count 1번이고, 첫 페이지가 다 차지 않으면 count 를 건너뛴다 ({@link PageableExecutionUtils#getPage}).
     *
     * <p>조건은 모두 product 행 기준이다. 카테고리는 {@code product_category} EXISTS({@code idx_product_category_category_id_product_id}),
     * 판매 가능 상태는 sku EXISTS({@code uk_sku_product_id_option_value_key} 선두 product_id) · stock PK 로 건다 — JOIN 하지 않아 행이 겹치지 않고
     * count 가 상품 수 그대로다. 상품명 부분 일치는 앞이 열린 {@code like} 라 인덱스를 타지 않는다 — 어드민 전용 · 상품 수 규모에서 product 표 스캔을 받아들인다.
     *
     * <p>빈 카테고리 집합 검사는 방어용이다 — "빈 집합이면 쿼리 없이 빈 Page" 계약은 어댑터({@code ProductRepositoryAdapter})가 지킨다.
     */
    @Override
    public Page<ProductAdminListQueryResult> searchForAdmin(ProductAdminSearchCriteria criteria) {
        PageRequest pageRequest = PageRequest.of(criteria.page(), criteria.size());
        Set<Long> categoryIds = criteria.categoryIds();
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageRequest, 0);
        }

        // 목록 · count 가 같은 배열을 쓴다 — 삭제 제외도 여기 한 번만 건다
        Predicate[] conditions = {
            notDeleted(),
            nameContains(criteria.keyword()),
            displayedInAny(categoryIds),
            criteria.saleStatus() == null ? null : productEntity.saleStatus.eq(criteria.saleStatus()),
            criteria.visible() == null ? null : productEntity.visible.eq(criteria.visible()),
            availabilityIs(criteria.availability())
        };

        List<ProductAdminListQueryResult> rows = queryFactory
            .select(Projections.constructor(
                ProductAdminListQueryResult.class,
                productEntity.id, productEntity.name, productEntity.salePriceAmount, productEntity.salePriceCurrency,
                productEntity.listPriceAmount, productEntity.listPriceCurrency, productEntity.saleStatus, productEntity.visible, productEntity.updatedAt
            ))
            .from(productEntity)
            .where(conditions)
            .orderBy(adminOrderOf(criteria.sortType()))
            .offset(pageRequest.getOffset())
            .limit(criteria.size())
            .fetch();

        JPAQuery<Long> count = queryFactory.select(productEntity.count()).from(productEntity).where(conditions);
        return PageableExecutionUtils.getPage(List.copyOf(rows), pageRequest, count::fetchOne);
    }

    /**
     * 삭제되지 않은 상품이 이 카테고리를 진열 또는 대표 카테고리로 쓰는가 (어드민 카테고리 삭제 판정) — 숨김 · 판매 종료 상품은 센다.
     *
     * <p>진열 카테고리({@code product_category} → product 조인, {@code idx_product_category_category_id_product_id} 선두)를 먼저 보고,
     * 없을 때만 대표 카테고리를 본다 — 불변식(대표는 진열에도 있다)이 지켜진 데이터면 첫 질의에서 끝난다. {@code product.category_id} 에는 인덱스가 없어
     * 두 번째 질의는 표를 훑는다. 어드민 카테고리 삭제에서만 부르므로 인덱스를 더하지 않는다. 둘 다 한 행만 읽는다({@code limit 1}).
     */
    @Override
    public boolean existsLiveProductInCategory(long categoryId) {
        Integer displayed = queryFactory
            .selectOne()
            .from(productCategoryEntity)
            .innerJoin(productEntity).on(productEntity.id.eq(productCategoryEntity.productId))
            .where(productCategoryEntity.categoryId.eq(categoryId), notDeleted())
            .fetchFirst();
        if (displayed != null) {
            return true;
        }
        return queryFactory
            .selectOne()
            .from(productEntity)
            .where(productEntity.categoryId.eq(categoryId), notDeleted())
            .fetchFirst() != null;
    }

    /**
     * 고객에게 보이는 상품 조건 — 노출 중이고 삭제되지 않았다. <b>모든 고객 쿼리가 이것을 쓴다</b> — 노출 조건이 늘면 여기만 고친다.
     * 삭제 상품은 DB check({@code ck_product_deleted_hidden})로 숨김이지만, 고객 경로는 그 check 에 기대지 않고 삭제 조건을 명시한다.
     */
    private static BooleanExpression visibleToCustomer() {
        return productEntity.visible.isTrue().and(notDeleted());
    }

    private static BooleanExpression notDeleted() {
        return productEntity.deletedAt.isNull();
    }

    private static BooleanExpression displayedInAny(Set<Long> categoryIds) {
        if (categoryIds == null) {
            return null;
        }
        return JPAExpressions.selectOne()
            .from(productCategoryEntity)
            .where(productCategoryEntity.productId.eq(productEntity.id), productCategoryEntity.categoryId.in(categoryIds))
            .exists();
    }

    private static OrderSpecifier<?>[] orderOf(ProductSortType sortType) {
        return switch (sortType) {
            case NEWEST -> new OrderSpecifier<?>[] {productEntity.id.desc()};
            case PRICE_ASC -> new OrderSpecifier<?>[] {productEntity.salePriceAmount.asc(), productEntity.id.desc()};
            case PRICE_DESC -> new OrderSpecifier<?>[] {productEntity.salePriceAmount.desc(), productEntity.id.desc()};
        };
    }

    /**
     * 상품명 부분 일치(대소문자 무시). QueryDSL 이 {@code %} · {@code _} · 이스케이프 문자를 이스케이프해 {@code like ... escape '!'} 로 보낸다 —
     * 검색어의 {@code %} 는 와일드카드가 아니라 글자다.
     */
    private static BooleanExpression nameContains(String keyword) {
        return keyword == null ? null : productEntity.name.containsIgnoreCase(keyword);
    }

    /**
     * 재고를 반영한 판매 가능 상태 조건 — {@code SaleAvailability#ofProduct} 를 SQL 로 옮긴 것이다. 목록 항목의 상태는 같은 규칙을 SKU 집계
     * ({@code SkuCustomRepositoryImpl#aggregateByProductIds})로 계산하므로, 판매중 · 재고 있음 판정은 그쪽 식({@code onSale} · {@code inStock})을 함께 쓴다.
     *
     * <ul>
     *   <li>판매 종료 = 상품이 판매 종료이거나 판매중 SKU 가 없다(SKU 0건 포함)</li>
     *   <li>판매중 = 상품이 판매중이고 구매 가능한 SKU(판매중 + 재고 있음)가 있다</li>
     *   <li>품절 = 상품이 판매중이고 판매중 SKU 는 있지만 구매 가능한 SKU 는 없다 — 재고 행이 없는 SKU 는 품절이다</li>
     * </ul>
     */
    private static BooleanExpression availabilityIs(SaleAvailability availability) {
        if (availability == null) {
            return null;
        }
        return switch (availability) {
            case ON_SALE -> productEntity.saleStatus.eq(SaleStatus.ON_SALE).and(hasPurchasableSku());
            case SOLD_OUT -> productEntity.saleStatus.eq(SaleStatus.ON_SALE).and(hasOnSaleSku()).and(hasPurchasableSku().not());
            case STOP_SELLING -> productEntity.saleStatus.eq(SaleStatus.STOP_SELLING).or(hasOnSaleSku().not());
        };
    }

    private static BooleanExpression hasOnSaleSku() {
        QSkuEntity sku = new QSkuEntity("onSaleSku");
        return JPAExpressions.selectOne()
            .from(sku)
            .where(sku.productId.eq(productEntity.id), SkuCustomRepositoryImpl.onSale(sku))
            .exists();
    }

    /** stock 은 PK 가 sku_id 라 SKU 마다 많아야 한 행이다. 재고 행이 없는 SKU 는 inner join 에서 빠져 품절로 센다. */
    private static BooleanExpression hasPurchasableSku() {
        QSkuEntity sku = new QSkuEntity("purchasableSku");
        QStockEntity stock = new QStockEntity("purchasableStock");
        return JPAExpressions.selectOne()
            .from(sku)
            .innerJoin(stock).on(stock.skuId.eq(sku.id))
            .where(sku.productId.eq(productEntity.id), SkuCustomRepositoryImpl.onSale(sku), SkuCustomRepositoryImpl.inStock(stock))
            .exists();
    }

    private static OrderSpecifier<?>[] adminOrderOf(ProductAdminSortType sortType) {
        return switch (sortType) {
            case NEWEST -> new OrderSpecifier<?>[] {productEntity.id.desc()};
            case NAME_ASC -> new OrderSpecifier<?>[] {productEntity.name.asc(), productEntity.id.desc()};
        };
    }
}
