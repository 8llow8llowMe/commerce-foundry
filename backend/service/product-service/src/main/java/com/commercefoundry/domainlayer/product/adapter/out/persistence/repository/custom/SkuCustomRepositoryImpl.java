package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QSkuEntity.skuEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QStockEntity.stockEntity;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QSkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QStockEntity;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuAggregateQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
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
     * 상품별로 "판매중 SKU 가 있는가", "구매 가능한 SKU 가 있는가" 와 판매중 SKU 추가금의 최소 · 최대를 한 번의 group by 로 낸다.
     *
     * <ul>
     *   <li>{@code max(case when 판매중 then 1 else 0 end)} 가 1 이면 판매중 SKU 가 하나 이상 있다</li>
     *   <li>{@code max(case when 판매중 and 재고 있음 then 1 else 0 end)} 가 1 이면 구매 가능한 SKU 가 하나 이상 있다.
     *       재고 있음 = 재고 행이 있고 무제한이거나 판매 가능 수량 &gt; 0 — {@code StockSnapshot#isSoldOut} 의 부정과 같다.
     *       재고 행이 없는 SKU 는 left join 결과가 null 이라 품절로 센다 ({@code StockSnapshot#empty})</li>
     *   <li>{@code min · max(case when 판매중 then 추가금 else null end)} — 집계 함수는 null 을 건너뛰므로 판매 종료 SKU 의 추가금은
     *       섞이지 않고, 판매중 SKU 가 없으면 null 이다</li>
     * </ul>
     *
     * <p>stock 은 PK 가 {@code sku_id} 라 SKU 행마다 많아야 한 행이 붙는다 — left join 이 SKU 행을 늘리지 않는다.
     *
     * <p>{@code count(sku.id)} 는 판매 상태와 무관한 SKU 수다(어드민 목록의 skuCount). left join 이 행을 늘리지 않아 그대로 센다.
     *
     * <p>1 · 0 은 바인드 파라미터가 아니라 리터럴로 넣는다 — 집계 함수 안의 타입 없는 파라미터는 PostgreSQL 이 타입을 정하지 못한다.
     * 집계 결과의 Java 타입(Integer · Long)은 방언 · 버전에 따라 달라질 수 있어 {@link Number} 로 받아 바꾼다.
     *
     * <p>빈 컬렉션 검사는 방어용이다 — "빈 컬렉션이면 쿼리 없이 빈 결과" 계약은 어댑터({@code SkuRepositoryAdapter})가 지킨다.
     */
    @Override
    public List<SkuAggregateQueryResult> aggregateByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }

        BooleanExpression onSale = onSale(skuEntity);
        BooleanExpression inStock = inStock(stockEntity);
        NumberExpression<Integer> hasOnSaleSku = flagMax(onSale);
        NumberExpression<Integer> hasPurchasableSku = flagMax(onSale.and(inStock));
        NumberExpression<Long> onSaleAdditionalAmount = new CaseBuilder()
            .when(onSale).then(skuEntity.additionalPriceAmount)
            .otherwise(Expressions.nullExpression(Long.class));
        NumberExpression<Long> minOnSaleAdditionalAmount = onSaleAdditionalAmount.min();
        NumberExpression<Long> maxOnSaleAdditionalAmount = onSaleAdditionalAmount.max();

        List<Tuple> rows = queryFactory
            .select(skuEntity.productId, hasOnSaleSku, hasPurchasableSku, minOnSaleAdditionalAmount, maxOnSaleAdditionalAmount, skuEntity.id.count())
            .from(skuEntity)
            .leftJoin(stockEntity).on(stockEntity.skuId.eq(skuEntity.id))
            .where(skuEntity.productId.in(productIds))
            .groupBy(skuEntity.productId)
            .fetch();

        return rows.stream()
            .map(row -> new SkuAggregateQueryResult(
                row.get(skuEntity.productId),
                isOne(row.get(1, Number.class)),
                isOne(row.get(2, Number.class)),
                toLong(row.get(3, Number.class)),
                toLong(row.get(4, Number.class)),
                row.get(5, Number.class).longValue()
            ))
            .toList();
    }

    /**
     * 판매중 SKU. 어드민 목록의 판매 가능 상태 조건({@code ProductCustomRepositoryImpl})도 이것을 쓴다 — 집계와 조건이 같은 규칙이어야
     * 목록에 보이는 상태와 필터 결과가 어긋나지 않는다.
     */
    static BooleanExpression onSale(QSkuEntity sku) {
        return sku.saleStatus.eq(SaleStatus.ON_SALE);
    }

    /**
     * 재고 있음 — 재고 행이 있고 무제한이거나 판매 가능 수량 &gt; 0 ({@code StockSnapshot#isSoldOut} 의 부정). 재고 행이 없으면(left join 의 null) 품절이다.
     * 어드민 목록의 판매 가능 상태 조건도 이것을 쓴다.
     */
    static BooleanExpression inStock(QStockEntity stock) {
        return stock.skuId.isNotNull().and(stock.stockManaged.isFalse().or(stock.availableQuantity.gt(0L)));
    }

    /** {@code max(case when condition then 1 else 0 end)} — 조건을 만족하는 행이 하나라도 있으면 1. */
    private static NumberExpression<Integer> flagMax(BooleanExpression condition) {
        return new CaseBuilder()
            .when(condition).then(Expressions.ONE)
            .otherwise(Expressions.ZERO)
            .max();
    }

    private static boolean isOne(Number flag) {
        return flag != null && flag.intValue() == 1;
    }

    private static Long toLong(Number value) {
        return value == null ? null : value.longValue();
    }
}
