package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QSkuEntity.skuEntity;

import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SkuCustomRepositoryImpl implements SkuCustomRepository {

    private final JPAQueryFactory queryFactory;

    /**
     * 상품별로 "추가금 있는 SKU 가 있는가" 와 "판매중 SKU 가 있는가" 를 한 번의 group by 로 센다.
     *
     * <p>{@code max(case when 조건 then 1 else 0 end)} 가 1 이면 조건을 만족하는 SKU 가 하나 이상 있다.
     * 1 · 0 은 바인드 파라미터가 아니라 리터럴로 넣는다 — 집계 함수 안의 타입 없는 파라미터는 PostgreSQL 이 타입을 정하지 못한다.
     */
    @Override
    public List<SkuAggregateQueryResult> aggregateByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }

        NumberExpression<Integer> hasOptionSurcharge = new CaseBuilder()
            .when(skuEntity.additionalPriceAmount.gt(0L)).then(Expressions.ONE)
            .otherwise(Expressions.ZERO)
            .max();
        NumberExpression<Integer> hasSellableSku = new CaseBuilder()
            .when(skuEntity.saleStatus.eq(SaleStatus.ON_SALE)).then(Expressions.ONE)
            .otherwise(Expressions.ZERO)
            .max();

        List<Tuple> rows = queryFactory
            .select(skuEntity.productId, hasOptionSurcharge, hasSellableSku)
            .from(skuEntity)
            .where(skuEntity.productId.in(productIds))
            .groupBy(skuEntity.productId)
            .fetch();

        return rows.stream()
            .map(row -> new SkuAggregateQueryResult(
                row.get(skuEntity.productId),
                isOne(row.get(hasOptionSurcharge)),
                isOne(row.get(hasSellableSku))
            ))
            .toList();
    }

    private static boolean isOne(Integer flag) {
        return flag != null && flag == 1;
    }
}
