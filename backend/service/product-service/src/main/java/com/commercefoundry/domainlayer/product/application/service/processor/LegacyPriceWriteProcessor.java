package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.port.out.PriceBookRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan.MissingBase;
import com.commercefoundry.global.properties.PriceSourceProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.List;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다섯 legacy writer가 공유하는 B1b 가격 조율기. MANDATORY는 TX 존재만 확인하며 부모 보호를 증명하지 않는다.
 * 호출 writer는 같은 상품의 CAS/@Version 보호 아래 같은 TX에서 prepare와 write를 호출해야 한다.
 *
 * <p>writer 진입({@link #prepare})은 가격 정본 모드를 따른다(#20 B3): LEGACY 는 빠진 기준통화 행을 legacy 값으로 보완(REPAIR),
 * PRICE_BOOK 은 보완하지 않고 거절(REJECT)한다. 전환 전 backfill 은 모드와 상관없이 {@link #prepareBackfill}(REPAIR 고정)을 쓴다.
 * PRICE_BOOK 은 B7 까지 {@link com.commercefoundry.global.config.PriceSourceModeGuard} 가 기동을 거절한다.
 */
@Component
@RequiredArgsConstructor
public class LegacyPriceWriteProcessor {
    private final PriceBookRepositoryPort priceBookRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final PriceSourceProperties priceSourceProperties;

    /** 기존 writer 진입. 빠진 기준통화 행 처리는 가격 정본 모드를 따른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LegacyPriceWritePlan prepare(LegacyPriceSnapshot before, LegacyPriceSnapshot after) {
        return prepare(before, after, missingBaseOf(priceSourceProperties.mode()));
    }

    /** 전환 전 backfill 진입. 빠진 기준통화 행을 legacy 값으로 채우는 것이 목적이라 항상 REPAIR 다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LegacyPriceWritePlan prepareBackfill(LegacyPriceSnapshot before, LegacyPriceSnapshot after) {
        return prepare(before, after, MissingBase.REPAIR);
    }

    static MissingBase missingBaseOf(PriceSourceProperties.Mode mode) {
        return switch (mode) {
            case LEGACY -> MissingBase.REPAIR;
            case PRICE_BOOK -> MissingBase.REJECT;
        };
    }

    private LegacyPriceWritePlan prepare(LegacyPriceSnapshot before, LegacyPriceSnapshot after, MissingBase missingBase) {
        before.requireSuccessor(after);
        var skuIds = new TreeSet<>(before.skuStatuses().keySet());
        skuIds.addAll(after.skuStatuses().keySet());
        var products = priceBookRepositoryPort.findProductPrices(List.of(before.productId()));
        var skus = priceBookRepositoryPort.findSkuPrices(skuIds);
        return LegacyPriceWritePlan.prepare(before, after, products, skus, snowflakeIdGenerator::generateId, missingBase);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LegacyPriceWritePlan prepareNew(LegacyPriceSnapshot after) {
        var products = priceBookRepositoryPort.findProductPrices(List.of(after.productId()));
        var skus = priceBookRepositoryPort.findSkuPrices(new TreeSet<>(after.skuStatuses().keySet()));
        return LegacyPriceWritePlan.prepareNew(after, products, skus, snowflakeIdGenerator::generateId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(LegacyPriceWritePlan plan) {
        if (!plan.productInserts().isEmpty() || !plan.skuInserts().isEmpty()) {
            priceBookRepositoryPort.insertAll(plan.productInserts(), plan.skuInserts());
        }
        if (!plan.productUpdates().isEmpty() || !plan.skuUpdates().isEmpty()) {
            priceBookRepositoryPort.updateAll(plan.productUpdates(), plan.skuUpdates());
        }
    }
}
