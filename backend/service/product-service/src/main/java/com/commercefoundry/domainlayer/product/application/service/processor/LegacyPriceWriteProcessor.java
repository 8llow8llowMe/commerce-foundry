package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.port.out.PriceBookRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan;
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
 */
@Component
@RequiredArgsConstructor
public class LegacyPriceWriteProcessor {
    private final PriceBookRepositoryPort priceBookRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    @Transactional(propagation = Propagation.MANDATORY)
    public LegacyPriceWritePlan prepare(LegacyPriceSnapshot before, LegacyPriceSnapshot after) {
        before.requireSuccessor(after);
        var skuIds = new TreeSet<>(before.skuStatuses().keySet());
        skuIds.addAll(after.skuStatuses().keySet());
        var products = priceBookRepositoryPort.findProductPrices(List.of(before.productId()));
        var skus = priceBookRepositoryPort.findSkuPrices(skuIds);
        return LegacyPriceWritePlan.prepare(before, after, products, skus, snowflakeIdGenerator::generateId);
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
