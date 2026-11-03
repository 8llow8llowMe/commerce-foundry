package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QProductEntity.productEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QSkuEntity.skuEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QStockAlertPolicyEntity.stockAlertPolicyEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QStockEntity.stockEntity;

import com.commercefoundry.domainlayer.product.application.port.out.query.StockAlertQueryResult;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@RequiredArgsConstructor
public class StockAlertPolicyCustomRepositoryImpl implements StockAlertPolicyCustomRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<StockAlertQueryResult> findLowStocks(Pageable pageable) {
        NumberExpression<Long> available = availableQuantity();
        BooleanExpression eligible = eligible();
        List<StockAlertQueryResult> rows = lowStockQuery().where(eligible)
            .orderBy(available.asc(), skuEntity.id.asc()).offset(pageable.getOffset()).limit(pageable.getPageSize()).fetch();
        Long total = joined(queryFactory.select(stockAlertPolicyEntity.skuId.count())).where(eligible).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @Override
    public List<StockAlertQueryResult> findLowStocksAfter(long afterSkuId, int limit) {
        return lowStockQuery().where(eligible(), skuEntity.id.gt(afterSkuId)).orderBy(skuEntity.id.asc()).limit(limit).fetch();
    }

    private JPAQuery<StockAlertQueryResult> lowStockQuery() {
        return joined(queryFactory.select(Projections.constructor(StockAlertQueryResult.class,
            skuEntity.id, productEntity.id, productEntity.name, skuEntity.optionValueKey, availableQuantity(), stockAlertPolicyEntity.thresholdQuantity,
            productEntity.visible, productEntity.saleStatus, skuEntity.saleStatus, stockAlertPolicyEntity.updatedAt)));
    }

    private BooleanExpression eligible() {
        return productEntity.deletedAt.isNull().and(stockEntity.stockManaged.coalesce(true).isTrue())
            .and(availableQuantity().loe(stockAlertPolicyEntity.thresholdQuantity));
    }

    private NumberExpression<Long> availableQuantity() {
        return stockEntity.availableQuantity.coalesce(0L);
    }

    private <T> JPAQuery<T> joined(JPAQuery<T> query) {
        return query.from(stockAlertPolicyEntity)
            .join(skuEntity).on(skuEntity.id.eq(stockAlertPolicyEntity.skuId))
            .join(productEntity).on(productEntity.id.eq(skuEntity.productId))
            .leftJoin(stockEntity).on(stockEntity.skuId.eq(skuEntity.id));
    }
}
