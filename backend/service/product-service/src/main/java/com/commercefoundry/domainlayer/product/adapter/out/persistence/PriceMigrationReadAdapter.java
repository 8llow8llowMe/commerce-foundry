package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QProductEntity.productEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QProductPriceEntity.productPriceEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QSkuEntity.skuEntity;
import static com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.QSkuPriceEntity.skuPriceEntity;
import static com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException.Reason.INVALID_CRITERIA;
import static com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException.Reason.READ_FAILED;

import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Bounds;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductPriceRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuPriceRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuRow;
import com.commercefoundry.domainlayer.product.application.port.out.PriceMigrationReadPort;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 조인·Money·enum hydration 없이 원천 행을 보존한다. 페이지마다 별도 snapshot이며 정합성 인증은 하지 않는다. */
@Repository
public class PriceMigrationReadAdapter implements PriceMigrationReadPort {
    private final JPAQueryFactory query;
    private final TransactionTemplate transaction;

    public PriceMigrationReadAdapter(JPAQueryFactory query, PlatformTransactionManager manager) {
        this.query = query;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
    }

    @Override
    public Bounds captureBounds() {
        return read(() -> new Bounds(max(query.select(productEntity.id.max()).from(productEntity)),
            max(query.select(skuEntity.id.max()).from(skuEntity)), max(query.select(productPriceEntity.id.max()).from(productPriceEntity)),
            max(query.select(skuPriceEntity.id.max()).from(skuPriceEntity))));
    }

    @Override
    public List<Row> readPage(Criteria criteria) {
        if (criteria == null) {
            throw new PriceMigrationReadException(INVALID_CRITERIA);
        }
        if (criteria.upper() == null || criteria.after() != null && criteria.after() >= criteria.upper()) {
            return List.of();
        }
        return read(() -> switch (criteria.lane()) {
            case PRODUCT -> page(query.select(Projections.constructor(ProductRow.class, productEntity.id,
                productEntity.salePriceAmount, productEntity.salePriceCurrency.stringValue(), productEntity.listPriceAmount,
                productEntity.listPriceCurrency.stringValue(), productEntity.saleStatus.stringValue(), productEntity.visible,
                productEntity.deletedAt, productEntity.version)).from(productEntity), productEntity.id, criteria);
            case SKU -> page(query.select(Projections.constructor(SkuRow.class, skuEntity.id, skuEntity.productId,
                skuEntity.additionalPriceAmount, skuEntity.additionalPriceCurrency.stringValue(), skuEntity.saleStatus.stringValue()))
                .from(skuEntity), skuEntity.id, criteria);
            case PRODUCT_PRICE -> page(query.select(Projections.constructor(ProductPriceRow.class, productPriceEntity.id,
                productPriceEntity.productId, productPriceEntity.salePriceAmount, productPriceEntity.salePriceCurrency.stringValue(),
                productPriceEntity.listPriceAmount, productPriceEntity.listPriceCurrency.stringValue())).from(productPriceEntity), productPriceEntity.id, criteria);
            case SKU_PRICE -> page(query.select(Projections.constructor(SkuPriceRow.class, skuPriceEntity.id, skuPriceEntity.skuId,
                skuPriceEntity.additionalPriceAmount, skuPriceEntity.additionalPriceCurrency.stringValue())).from(skuPriceEntity), skuPriceEntity.id, criteria);
        });
    }

    private Long max(JPAQuery<Long> selection) {
        return selection.setHint("jakarta.persistence.query.timeout", 2000).fetchOne();
    }

    private <T extends Row> List<Row> page(JPAQuery<T> selection, NumberPath<Long> id, Criteria criteria) {
        selection.where(id.loe(criteria.upper()));
        if (criteria.after() != null) {
            selection.where(id.gt(criteria.after()));
        }
        return List.copyOf(selection.orderBy(id.asc()).limit(criteria.pageSize()).setHint("jakarta.persistence.query.timeout", 2000).fetch());
    }

    private <T> T read(Supplier<T> action) {
        // execute 바깥에서 번역해야 트랜잭션 시작·쿼리·커밋 실패 모두 원문을 버린다.
        try {
            return transaction.execute(status -> {
                try {
                    return action.get();
                } catch (RuntimeException ignored) {
                    // TransactionTemplate의 rollback DEBUG 로그에도 원래 예외가 전달되지 않게 한다.
                    throw new PriceMigrationReadException(READ_FAILED);
                }
            });
        } catch (RuntimeException ignored) {
            throw new PriceMigrationReadException(READ_FAILED);
        }
    }
}
