package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.OptionRetirementCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuAdminRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductPricing;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 옵션 그룹 · 값 은퇴 (#9 PR-C2).
 *
 * <p>행은 지우지 않고 {@code retiredAt} 만 채운다 — 주문 · 장바구니가 옵션명을 계속 읽는다. 은퇴한 값(은퇴한 그룹의 값 포함)을 쓰는 판매중 SKU 는
 * 같은 트랜잭션에서 판매 종료로 바꾼다(api-design-guide §2-13 의 "은퇴 값을 쓰는 SKU 는 판매 종료" 를 쓰기가 보장한다). 재고 · 선점 · 원가 · 주문 스냅숏은 그대로다.
 * 이미 은퇴한 대상은 아무것도 하지 않는다. 은퇴 되돌리기는 v1 에 없다(#9 PR-C 권장안).
 */
@Component
@RequiredArgsConstructor
public class OptionRetirementProcessor {

    private final ProductRepositoryPort products;
    private final SkuRepositoryPort skus;
    private final ProductOptionRepositoryPort options;
    private final SkuAdminRepositoryPort writes;
    private final LegacyPriceWriteProcessor legacyPrices;

    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(OptionRetirementCommand command) {
        Product before = product(command.productId());
        if (before.version() == null || before.version() != command.version()) {
            throw new SkuAdminException(SkuAdminErrorCode.VERSION_CONFLICT);
        }
        Map<Long, OptionGroup> groups = options.findGroupsByProductId(command.productId()).stream()
            .collect(Collectors.toMap(OptionGroup::id, Function.identity()));
        Map<Long, OptionValue> values = options.findValuesByProductId(command.productId()).stream()
            .collect(Collectors.toMap(OptionValue::id, Function.identity()));
        requireOwned(command.optionGroupIds(), groups.keySet(), "이 상품의 옵션이 아닙니다");
        requireOwned(command.optionValueIds(), values.keySet(), "이 상품의 옵션 값이 아닙니다");

        Set<Long> groupTargets = command.optionGroupIds().stream().filter(id -> !groups.get(id).isRetired()).collect(Collectors.toSet());
        Set<Long> valueTargets = command.optionValueIds().stream().filter(id -> !values.get(id).isRetired()).collect(Collectors.toSet());
        if (groupTargets.isEmpty() && valueTargets.isEmpty()) {
            return; // 이미 모두 은퇴했다 — 버전도 그대로
        }
        if (!writes.claimProductVersion(command.productId(), command.version())) {
            throw new SkuAdminException(SkuAdminErrorCode.VERSION_CONFLICT);
        }
        // CAS 뒤(상품 행 잠금 아래)에서 옵션을 다시 읽어 판매 종료 대상을 정한다. 옵션 · SKU 를 바꾸는 쓰기는 모두 상품 버전을 올린다는 전제다
        // (PUT /options · PUT /skus · POST /skus · 이 API) — 버전을 올리지 않는 쓰기가 생기면 아래 SKU 덮어쓰기가 그 변경을 지운다.
        Map<Long, OptionGroup> lockedGroups = options.findGroupsByProductId(command.productId()).stream().collect(Collectors.toMap(OptionGroup::id, Function.identity()));
        Map<Long, OptionValue> lockedValues = options.findValuesByProductId(command.productId()).stream().collect(Collectors.toMap(OptionValue::id, Function.identity()));

        // 은퇴 뒤 고를 수 없는 값 = 이미 은퇴했거나 이번에 은퇴하는 값 · 그 그룹의 값
        Set<Long> unavailable = new HashSet<>();
        for (OptionValue value : lockedValues.values()) {
            OptionGroup group = lockedGroups.get(value.optionGroupId());
            if (value.isRetired() || valueTargets.contains(value.id()) || group == null || group.isRetired() || groupTargets.contains(group.id())) {
                unavailable.add(value.id());
            }
        }
        Product current = product(command.productId());
        List<Sku> existing = skus.findAllByProductId(command.productId());
        List<Sku> stopped = existing.stream()
            .filter(sku -> sku.isOnSale() && sku.optionKey().valueIds().stream().anyMatch(unavailable::contains))
            .map(sku -> sku.withEditableProperties(sku.additionalPrice(), SaleStatus.STOP_SELLING, sku.weightGrams(), sku.hsCode()))
            .toList();
        Set<Long> stoppedIds = stopped.stream().map(Sku::id).collect(Collectors.toSet());
        // 남는 판매중 SKU 로 "기본 판매가 = 판매중 최저가" 가 서야 한다 — 아니면 가격을 먼저 고치게 거절한다
        ProductPricing.violation(current.salePrice(), current.listPrice(), existing.stream()
            .map(sku -> new ProductPricing.SkuPrice(sku.additionalPrice(), stoppedIds.contains(sku.id()) ? SaleStatus.STOP_SELLING : sku.saleStatus()))
            .toList())
            .ifPresent(reason -> { throw new SkuAdminException(SkuAdminErrorCode.PRICE_INVALID, reason + " — 남는 판매중 SKU 의 추가금을 먼저 맞춰 주세요"); });

        var stoppedById = stopped.stream().collect(Collectors.toMap(Sku::id, Function.identity()));
        var after = existing.stream().map(sku -> stoppedById.getOrDefault(sku.id(), sku)).toList();
        try {
            var plan = legacyPrices.prepare(LegacyPriceSnapshot.of(current, existing), LegacyPriceSnapshot.of(current, after));
            options.retire(command.productId(), groupTargets, valueTargets, Instant.now().truncatedTo(ChronoUnit.MICROS));
            writes.replaceEditableProperties(stopped);
            legacyPrices.write(plan);
        } catch (LegacyPriceWritePlan.RequestRuleViolationException exception) {
            throw new SkuAdminException(SkuAdminErrorCode.PRICE_INVALID, exception.getMessage());
        }
    }

    private static void requireOwned(Set<Long> requested, Set<Long> owned, String reason) {
        List<String> foreign = requested.stream().filter(id -> !owned.contains(id)).sorted().map(String::valueOf).toList();
        if (!foreign.isEmpty()) {
            throw new SkuAdminException(SkuAdminErrorCode.OPTION_RETIREMENT_INVALID, reason + "(" + String.join(", ", foreign) + ")");
        }
    }

    private Product product(long productId) {
        return products.findAllByIdsIncludingHidden(List.of(productId)).stream().filter(product -> !product.isDeleted()).findFirst()
            .orElseThrow(() -> new SkuAdminException(SkuAdminErrorCode.PRODUCT_NOT_FOUND));
    }
}
