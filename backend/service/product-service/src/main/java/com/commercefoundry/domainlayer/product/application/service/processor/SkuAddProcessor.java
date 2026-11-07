package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.SkuAddCommand;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuAdminRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductPricing;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.domainlayer.product.domain.model.StockSnapshot;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 상품에 옵션 값 · 새 조합 SKU 를 더한다 (#9 PR-C1).
 *
 * <p>기존 행(옵션 · SKU · 재고 · 선점 · 주문 스냅숏)은 바꾸지 않고 새 행만 넣는다. 새 옵션 그룹은 받지 않는다 — 그룹이 늘면 기존 SKU 조합이 모두 맞지 않게 된다
 * (구조를 바꾸려면 새 상품으로 등록한다, #9 PR-C 권장안). 은퇴한 이름은 다시 쓰지 않는다(고유 제약이 은퇴 행을 포함한다).
 * 새 SKU 의 처음 재고는 등록(E1)과 같은 재고 행 + 입고 이력 1건이다. 상품 버전 CAS 가 모든 쓰기보다 먼저 동시 편집을 막는다.
 */
@Component
@RequiredArgsConstructor
public class SkuAddProcessor {

    /** 등록과 같은 상한. */
    static final int MAX_SKUS = 100;
    static final int MAX_VALUES_PER_GROUP = 50;
    static final String INITIAL_STOCK_MEMO = "SKU 추가 초기 재고";

    private final ProductRepositoryPort products;
    private final SkuRepositoryPort skus;
    private final ProductOptionRepositoryPort options;
    private final SkuAdminRepositoryPort writes;
    private final StockRepositoryPort stocks;
    private final StockAdjustmentHistoryRepositoryPort stockHistories;
    private final SnowflakeIdGenerator ids;
    private final LegacyPriceWriteProcessor legacyPrices;

    @Transactional(propagation = Propagation.MANDATORY)
    public void add(SkuAddCommand command) {
        Product before = product(command.productId());
        if (before.version() == null || before.version() != command.version()
            || !writes.claimProductVersion(command.productId(), command.version())) {
            throw new SkuAdminException(SkuAdminErrorCode.VERSION_CONFLICT);
        }
        // CAS 가 영속성 컨텍스트를 비운 뒤 최신 상태로 판정한다.
        Product current = product(command.productId());
        List<OptionGroup> groups = options.findGroupsByProductId(command.productId()).stream()
            .sorted(Comparator.comparingInt(OptionGroup::displayOrder).thenComparingLong(OptionGroup::id)).toList();
        List<OptionValue> values = options.findValuesByProductId(command.productId());
        List<Sku> existingSkus = skus.findAllByProductId(command.productId());

        if (groups.isEmpty()) {
            throw combination("옵션이 없는 상품에는 SKU 를 더할 수 없습니다");
        }
        if (groups.stream().anyMatch(OptionGroup::isRetired)) {
            throw combination("은퇴한 옵션이 있는 상품에는 새 조합을 더할 수 없습니다");
        }
        if (existingSkus.size() + command.skus().size() > MAX_SKUS) {
            throw new SkuAdminException(SkuAdminErrorCode.SKU_LIMIT_EXCEEDED);
        }

        List<OptionValue> added = newValues(command, groups, values);
        // 그룹마다 고를 수 있는 값 — 살아 있는 기존 값과 이번에 더한 값
        Map<Long, Map<String, OptionValue>> selectable = new HashMap<>();
        Stream.concat(values.stream().filter(value -> !value.isRetired()), added.stream())
            .forEach(value -> selectable.computeIfAbsent(value.optionGroupId(), key -> new HashMap<>()).put(value.name(), value));
        Set<String> existingKeys = existingSkus.stream().map(sku -> sku.optionKey().value()).collect(Collectors.toSet());

        List<Sku> newSkus = new ArrayList<>();
        Map<Long, SkuAddCommand.InitialStock> initialStocks = new HashMap<>();
        Set<String> requestedKeys = new HashSet<>();
        for (SkuAddCommand.NewSku source : command.skus()) {
            if (source.optionValues().size() != groups.size()) {
                throw combination("SKU 마다 옵션 " + groups.size() + "개의 값을 하나씩 골라야 합니다");
            }
            List<Long> valueIds = new ArrayList<>();
            for (int index = 0; index < groups.size(); index++) {
                OptionGroup group = groups.get(index);
                OptionValue value = selectable.getOrDefault(group.id(), Map.of()).get(source.optionValues().get(index));
                if (value == null) {
                    throw combination("옵션 '" + group.name() + "' 에 고를 수 있는 값이 아닙니다(" + source.optionValues().get(index) + ")");
                }
                valueIds.add(value.id());
            }
            SkuOptionKey key = SkuOptionKey.of(valueIds);
            String label = String.join(" / ", source.optionValues());
            if (existingKeys.contains(key.value()) || !requestedKeys.add(key.value())) {
                throw new SkuAdminException(SkuAdminErrorCode.COMBINATION_DUPLICATED, label);
            }
            long skuId = ids.generateId();
            newSkus.add(new Sku(skuId, command.productId(), key, source.additionalPrice(), source.saleStatus(), source.weightGrams(), source.hsCode()));
            if (source.initialStock() != null) {
                initialStocks.put(skuId, source.initialStock());
            }
        }

        // 기본 판매가 = 판매중 SKU 최저가 불변식은 기존 + 새 SKU 전체로 본다
        ProductPricing.violation(current.salePrice(), current.listPrice(), Stream.concat(existingSkus.stream(), newSkus.stream())
            .map(sku -> new ProductPricing.SkuPrice(sku.additionalPrice(), sku.saleStatus())).toList())
            .ifPresent(reason -> { throw new SkuAdminException(SkuAdminErrorCode.PRICE_INVALID, reason); });

        Set<Long> usedValueIds = newSkus.stream().flatMap(sku -> sku.optionKey().valueIds().stream()).collect(Collectors.toSet());
        added.stream().filter(value -> !usedValueIds.contains(value.id())).findFirst().ifPresent(value -> {
            // 고를 SKU 가 없는 값은 고객 상세에 고를 수 없는 선택지로 보인다
            throw new SkuAdminException(SkuAdminErrorCode.OPTION_VALUE_INVALID, "새 값(" + value.name() + ")을 쓰는 SKU 를 함께 보내 주세요");
        });

        options.insertAll(List.of(), added);
        skus.insertAll(newSkus);
        flushClassified();
        // Keep price failures outside the option constraint classification and before stock writes.
        try {
            legacyPrices.write(legacyPrices.prepare(LegacyPriceSnapshot.of(current, existingSkus),
                LegacyPriceSnapshot.of(current, Stream.concat(existingSkus.stream(), newSkus.stream()).toList())));
        } catch (LegacyPriceWritePlan.RequestRuleViolationException exception) {
            throw new SkuAdminException(SkuAdminErrorCode.PRICE_INVALID, exception.getMessage());
        }
        StockSnapshot empty = StockSnapshot.empty();
        initialStocks.forEach((skuId, initial) -> {
            Stock after = new Stock(skuId, initial.stockManaged(), initial.quantity());
            stocks.create(after);
            stockHistories.append(StockAdjustmentHistory.of(ids.generateId(), new Stock(skuId, empty.stockManaged(), empty.availableQuantity()),
                after, StockAdjustmentReason.RESTOCK, INITIAL_STOCK_MEMO, command.actorMemberId()));
        });
    }

    private List<OptionValue> newValues(SkuAddCommand command, List<OptionGroup> groups, List<OptionValue> values) {
        Map<Long, OptionGroup> groupsById = groups.stream().collect(Collectors.toMap(OptionGroup::id, group -> group));
        Map<Long, Set<String>> usedNames = new HashMap<>();
        Map<Long, Integer> nextOrder = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (OptionValue value : values) {
            usedNames.computeIfAbsent(value.optionGroupId(), key -> new HashSet<>()).add(value.name());
            nextOrder.merge(value.optionGroupId(), value.displayOrder() + 1, Math::max);
            counts.merge(value.optionGroupId(), 1, Integer::sum);
        }
        List<OptionValue> added = new ArrayList<>();
        for (SkuAddCommand.NewOptionValue input : command.newOptionValues()) {
            OptionGroup group = groupsById.get(input.optionGroupId());
            if (group == null) {
                throw new SkuAdminException(SkuAdminErrorCode.OPTION_VALUE_INVALID, "이 상품의 옵션이 아닙니다(" + input.optionGroupId() + ")");
            }
            if (!usedNames.computeIfAbsent(group.id(), key -> new HashSet<>()).add(input.name())) {
                throw new SkuAdminException(SkuAdminErrorCode.OPTION_VALUE_INVALID,
                    "옵션 '" + group.name() + "' 에 이미 쓴 이름입니다(" + input.name() + ") — 은퇴한 값의 이름도 다시 쓸 수 없습니다");
            }
            if (counts.merge(group.id(), 1, Integer::sum) > MAX_VALUES_PER_GROUP) {
                throw new SkuAdminException(SkuAdminErrorCode.OPTION_VALUE_INVALID, "옵션 '" + group.name() + "' 의 값은 " + MAX_VALUES_PER_GROUP + "개까지입니다");
            }
            int order = nextOrder.merge(group.id(), 0, (current, ignored) -> current);
            nextOrder.put(group.id(), order + 1);
            added.add(new OptionValue(ids.generateId(), group.id(), command.productId(), input.name(), order, null));
        }
        return added;
    }

    /**
     * 재고 행 INSERT 보다 먼저 옵션 값 · SKU 를 내보낸다 — 앞 단계의 제약 위반이 재고 충돌로 잘못 붙지 않게(등록과 같다).
     * 사전 검사와 CAS 로 정상 경로에서는 위반이 없지만, 나면 이 API 의 업무 코드로 바꾼다(조합 → 009, 그 밖의 값 · 이름 → 007).
     */
    private void flushClassified() {
        try {
            products.flushNewProduct();
        } catch (com.commercefoundry.domainlayer.product.application.exception.ProductException exception) {
            if (exception.getErrorCode() == com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode.PRODUCT_SKU_COMBINATION_DUPLICATED) {
                throw new SkuAdminException(SkuAdminErrorCode.COMBINATION_DUPLICATED, "옵션 조합을 확인해 주세요");
            }
            throw exception;
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new SkuAdminException(SkuAdminErrorCode.OPTION_VALUE_INVALID, "옵션 값 이름이 이미 있습니다");
        }
    }

    private Product product(long productId) {
        return products.findAllByIdsIncludingHidden(List.of(productId)).stream().filter(product -> !product.isDeleted()).findFirst()
            .orElseThrow(() -> new SkuAdminException(SkuAdminErrorCode.PRODUCT_NOT_FOUND));
    }

    private static SkuAdminException combination(String reason) {
        return new SkuAdminException(SkuAdminErrorCode.COMBINATION_INVALID, reason);
    }
}
