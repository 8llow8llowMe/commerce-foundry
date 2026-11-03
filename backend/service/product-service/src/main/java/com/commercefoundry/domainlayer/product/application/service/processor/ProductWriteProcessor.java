package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 상품 등록의 판정 · 저장 (#9 E1). 이미지(F2)는 별도 Processor 가 준비하고 같은 등록 트랜잭션에서 저장한다. 맞춤 제작 입력(E2)은 후속 범위다.
 *
 * <p>판정은 저장 전에 모두 끝낸다 — 옵션 · SKU 구성({@code PRODUCT_013} · {@code 014}), 가격 규칙({@code 016}), 카테고리 존재({@code 012}).
 * SKU 는 옵션 값 이름으로 조합을 고른다(그룹 순서대로 하나씩) — 값 아이디는 저장할 때 발급하므로 요청이 알 수 없다.
 *
 * <p>새 SKU 의 처음 재고(D6)는 재고 행과 조정 이력(RESTOCK) 1건을 같은 트랜잭션에서 만든다. 처음 재고가 없는 SKU 는 재고 행이 없어 품절로 생긴다
 * ({@link StockSnapshot#empty()}). 쓰기 메서드는 {@code MANDATORY} 다 — 카테고리 잠금과 저장이 한 트랜잭션이어야 한다.
 */
@Component
@RequiredArgsConstructor
public class ProductWriteProcessor {

    /** 처음 재고 이력의 메모 — 어드민 재고 이력 화면에 이 문구로 보인다. */
    static final String INITIAL_STOCK_MEMO = "상품 등록 초기 재고";

    private final ProductRepositoryPort productRepositoryPort;
    private final ProductCategoryRepositoryPort productCategoryRepositoryPort;
    private final ProductOptionRepositoryPort productOptionRepositoryPort;
    private final SkuRepositoryPort skuRepositoryPort;
    private final StockRepositoryPort stockRepositoryPort;
    private final StockAdjustmentHistoryRepositoryPort stockAdjustmentHistoryRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** 진열 카테고리 — 받은 순서에서 중복을 빼고, 대표 카테고리가 없으면 맨 앞에 더한다(대표는 진열에도 들어 있어야 한다). */
    public List<Long> displayCategoryIdsOf(ProductCreateCommand command) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (!command.categoryIds().contains(command.categoryId())) {
            ids.add(command.categoryId());
        }
        ids.addAll(command.categoryIds());
        return List.copyOf(ids);
    }

    /**
     * 저장 없이 볼 수 있는 규칙 — 옵션 · SKU 구성과 가격. 카테고리 잠금을 잡기 전에 불러 잠금 시간을 줄인다.
     *
     * @throws ProductException {@code PRODUCT_013} · {@code 014} · {@code 016}
     */
    public void validate(ProductCreateCommand command) {
        validateOptionNames(command.optionGroups());
        validateSkuCombinations(command);
        validatePricing(command);
    }

    /** 대표 · 진열 카테고리가 모두 있는가. 없는 아이디를 모두 모아 {@code PRODUCT_012}(400)다. {@code tree} 는 잠근 카테고리 트리다. */
    public void requireCategories(CategoryTree tree, List<Long> categoryIds) {
        List<String> missing = categoryIds.stream().filter(id -> tree.find(id).isEmpty()).map(String::valueOf).toList();
        if (!missing.isEmpty()) {
            throw new ProductException(ProductErrorCode.PRODUCT_CATEGORY_NOT_FOUND, String.join(", ", missing));
        }
    }

    /**
     * 상품 · 진열 카테고리 · 옵션 · SKU · 처음 재고를 저장한다. 판정은 {@link #validate} · {@link #requireCategories} 가 끝냈다.
     *
     * @param descriptionHtml 정제본
     * @param categoryIds {@link #displayCategoryIdsOf} 결과
     * @return 새 상품 아이디
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long create(ProductCreateCommand command, String descriptionHtml, List<Long> categoryIds) {
        return create(command, descriptionHtml, categoryIds, snowflakeIdGenerator.generateId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long create(ProductCreateCommand command, String descriptionHtml, List<Long> categoryIds, long productId) {
        productRepositoryPort.insert(new Product(
            productId, command.categoryId(), command.name(), command.summary(), descriptionHtml,
            command.saleStatus(), command.visible(), command.salePrice(), command.listPrice(), command.maxQuantityPerOrder(),
            command.customOrder().enabled(), command.customOrder().notice(), command.customOrder().leadTimeDays(),
            command.preorder(), command.expectedShipDate(), null, null, command.createdByMemberId()
        ));
        productCategoryRepositoryPort.insertLinks(productId, categoryIds, categoryIds.stream().map(id -> snowflakeIdGenerator.generateId()).toList());

        List<OptionGroup> groups = new ArrayList<>();
        List<OptionValue> values = new ArrayList<>();
        List<Map<String, Long>> valueIdsByGroup = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < command.optionGroups().size(); groupIndex++) {
            ProductCreateCommand.OptionGroup source = command.optionGroups().get(groupIndex);
            long groupId = snowflakeIdGenerator.generateId();
            groups.add(new OptionGroup(groupId, productId, source.name(), groupIndex, null));
            Map<String, Long> valueIds = new HashMap<>();
            for (int valueIndex = 0; valueIndex < source.values().size(); valueIndex++) {
                long valueId = snowflakeIdGenerator.generateId();
                values.add(new OptionValue(valueId, groupId, productId, source.values().get(valueIndex), valueIndex, null));
                valueIds.put(source.values().get(valueIndex), valueId);
            }
            valueIdsByGroup.add(valueIds);
        }
        productOptionRepositoryPort.insertAll(groups, values);

        List<Sku> skus = new ArrayList<>();
        Map<Long, ProductCreateCommand.InitialStock> initialStocks = new HashMap<>();
        for (ProductCreateCommand.Sku source : command.skus()) {
            List<Long> optionValueIds = new ArrayList<>();
            for (int groupIndex = 0; groupIndex < source.optionValues().size(); groupIndex++) {
                optionValueIds.add(valueIdsByGroup.get(groupIndex).get(source.optionValues().get(groupIndex)));
            }
            long skuId = snowflakeIdGenerator.generateId();
            skus.add(new Sku(
                skuId, productId, SkuOptionKey.of(optionValueIds), source.additionalPrice(), source.saleStatus(), source.weightGrams(),
                source.hsCode() == null ? null : new HsCode(source.hsCode())
            ));
            if (source.initialStock() != null) {
                initialStocks.put(skuId, source.initialStock());
            }
        }
        skuRepositoryPort.insertAll(skus);
        // 재고 행 INSERT(saveAndFlush)보다 먼저 내보낸다 — 앞 단계의 제약 위반이 재고 충돌(PRODUCT_020)로 잘못 붙지 않게
        productRepositoryPort.flushNewProduct();

        StockSnapshot empty = StockSnapshot.empty();
        initialStocks.forEach((skuId, initial) -> {
            Stock after = new Stock(skuId, initial.stockManaged(), initial.quantity());
            stockRepositoryPort.create(after);
            stockAdjustmentHistoryRepositoryPort.append(StockAdjustmentHistory.of(
                snowflakeIdGenerator.generateId(), new Stock(skuId, empty.stockManaged(), empty.availableQuantity()), after,
                StockAdjustmentReason.RESTOCK, INITIAL_STOCK_MEMO, command.createdByMemberId()
            ));
        });
        return productId;
    }

    // ── 판정 ────────────────────────────────────────────────────

    private static void validateOptionNames(List<ProductCreateCommand.OptionGroup> groups) {
        Set<String> groupNames = new HashSet<>();
        for (ProductCreateCommand.OptionGroup group : groups) {
            if (!groupNames.add(group.name())) {
                throw combinationInvalid("옵션 이름이 겹칩니다(" + group.name() + ")");
            }
            Set<String> valueNames = new HashSet<>();
            for (String value : group.values()) {
                if (!valueNames.add(value)) {
                    throw combinationInvalid("옵션 '" + group.name() + "' 안에 같은 값이 두 번 있습니다(" + value + ")");
                }
            }
        }
    }

    /** SKU 마다 그룹 순서대로 값 하나씩 — 옵션 없는 상품은 SKU 한 개에 값 없음. 같은 조합은 {@code 014}. */
    private static void validateSkuCombinations(ProductCreateCommand command) {
        List<ProductCreateCommand.OptionGroup> groups = command.optionGroups();
        if (groups.isEmpty() && command.skus().size() != 1) {
            throw combinationInvalid("옵션이 없는 상품은 SKU 를 한 개만 둡니다");
        }
        Set<List<String>> combinations = new HashSet<>();
        for (ProductCreateCommand.Sku sku : command.skus()) {
            List<String> chosen = sku.optionValues();
            if (chosen.size() != groups.size()) {
                throw combinationInvalid("SKU 마다 옵션 " + groups.size() + "개의 값을 하나씩 골라야 합니다");
            }
            for (int index = 0; index < chosen.size(); index++) {
                if (!groups.get(index).values().contains(chosen.get(index))) {
                    throw combinationInvalid("옵션 '" + groups.get(index).name() + "' 에 없는 값입니다(" + chosen.get(index) + ")");
                }
            }
            if (!combinations.add(chosen)) {
                throw new ProductException(ProductErrorCode.PRODUCT_SKU_COMBINATION_DUPLICATED, String.join(" / ", chosen));
            }
        }
    }

    /**
     * 통화는 모두 기본 판매가와 같다. 판매중 SKU 가 있으면 그중 추가금 0 이 하나 이상이다 — 기본 판매가 = 판매중 SKU 최저가
     * ({@code ProductPricing#displaySalePrice} 의 불변식). 판매중 SKU 가 없으면(전부 판매 종료) 따지지 않는다.
     */
    private static void validatePricing(ProductCreateCommand command) {
        ProductPricing.violation(command.salePrice(), command.listPrice(), command.skus().stream()
            .map(sku -> new ProductPricing.SkuPrice(sku.additionalPrice(), sku.saleStatus())).toList())
            .ifPresent(reason -> { throw priceRuleViolated(reason); });
    }

    private static ProductException combinationInvalid(String reason) {
        return new ProductException(ProductErrorCode.PRODUCT_OPTION_COMBINATION_INVALID, reason);
    }

    private static ProductException priceRuleViolated(String reason) {
        return new ProductException(ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED, reason);
    }
}
