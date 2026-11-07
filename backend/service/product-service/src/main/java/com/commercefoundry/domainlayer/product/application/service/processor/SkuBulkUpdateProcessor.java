package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.SkuBulkUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuAdminRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductPricing;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SkuBulkUpdateProcessor {
    private final ProductRepositoryPort products;
    private final SkuRepositoryPort skus;
    private final ProductOptionRepositoryPort options;
    private final SkuAdminRepositoryPort writes;
    private final LegacyPriceWriteProcessor legacyPrices;

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(SkuBulkUpdateCommand command) {
        Product before = product(command.productId());
        if (before.version() == null || before.version() != command.version()
            || !writes.claimProductVersion(command.productId(), command.version())) {
            throw new SkuAdminException(SkuAdminErrorCode.VERSION_CONFLICT);
        }
        // CAS가 persistence context를 비운 뒤 최신 가격·SKU로 불변식을 검증한다.
        Product current = product(command.productId());
        var existing = skus.findAllByProductId(command.productId());
        var requested = command.skus();
        var ids = requested.stream().map(SkuBulkUpdateCommand.Item::skuId).collect(Collectors.toSet());
        if (requested.isEmpty() || requested.size() > 100 || ids.size() != requested.size()
            || existing.size() != requested.size() || !ids.equals(existing.stream().map(Sku::id).collect(Collectors.toSet()))) {
            throw new SkuAdminException(SkuAdminErrorCode.SKU_SET_INVALID);
        }
        var byId = existing.stream().collect(Collectors.toMap(Sku::id, Function.identity()));
        var changed = requested.stream().map(item -> byId.get(item.skuId()).withEditableProperties(
            item.additionalPrice(), item.saleStatus(), item.weightGrams(), item.hsCode())).toList();
        ProductPricing.violation(current.salePrice(), current.listPrice(), changed.stream()
            .map(sku -> new ProductPricing.SkuPrice(sku.additionalPrice(), sku.saleStatus())).toList())
            .ifPresent(reason -> { throw new SkuAdminException(SkuAdminErrorCode.PRICE_INVALID, reason); });
        var groups = options.findGroupsByProductId(command.productId()).stream().collect(Collectors.toMap(group -> group.id(), Function.identity()));
        var values = options.findValuesByProductId(command.productId()).stream().collect(Collectors.toMap(value -> value.id(), Function.identity()));
        boolean retiredOnSale = changed.stream().filter(Sku::isOnSale).flatMap(sku -> sku.optionKey().valueIds().stream()).anyMatch(id -> {
            var value = values.get(id);
            var group = value == null ? null : groups.get(value.optionGroupId());
            return value == null || value.isRetired() || group == null || group.isRetired();
        });
        if (retiredOnSale) {
            throw new SkuAdminException(SkuAdminErrorCode.RETIRED_OPTION_ON_SALE);
        }
        try {
            var plan = legacyPrices.prepare(LegacyPriceSnapshot.of(current, existing), LegacyPriceSnapshot.of(current, changed));
            // The adapter saves and flushes in ascending SKU ID order before price rows become dirty.
            writes.replaceEditableProperties(changed);
            legacyPrices.write(plan);
        } catch (LegacyPriceWritePlan.RequestRuleViolationException exception) {
            throw new SkuAdminException(SkuAdminErrorCode.PRICE_INVALID, exception.getMessage());
        }
    }

    private Product product(long productId) {
        return products.findAllByIdsIncludingHidden(List.of(productId)).stream().filter(product -> !product.isDeleted()).findFirst()
            .orElseThrow(() -> new SkuAdminException(SkuAdminErrorCode.PRODUCT_NOT_FOUND));
    }
}
