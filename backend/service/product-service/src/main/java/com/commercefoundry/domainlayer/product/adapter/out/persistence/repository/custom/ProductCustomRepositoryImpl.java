package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QProductCategoryEntity.productCategoryEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QProductEntity.productEntity;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

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
     * 고객에게 보이는 상품 조건. <b>모든 고객 쿼리가 이것을 쓴다</b> — 삭제(soft delete) 같은 노출 조건이 늘면 여기만 고친다.
     */
    private static BooleanExpression visibleToCustomer() {
        return productEntity.visible.isTrue();
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
}
