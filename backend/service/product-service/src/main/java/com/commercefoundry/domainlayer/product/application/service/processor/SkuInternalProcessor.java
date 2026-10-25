package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.info.CustomOrderFieldDefinition;
import com.commercefoundry.domainlayer.product.application.info.SkuInternalInfo;
import com.commercefoundry.domainlayer.product.application.info.SkuOptionValueInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductPricing;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuStocks;
import com.commercefoundry.domainlayer.product.domain.model.StockSnapshot;
import com.commercefoundry.global.properties.StoreProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 서비스 간 SKU 벌크 조회 (주문 생성 · 장바구니 가격 확인). 쿼리는 SKU → 상품(숨김 포함) → 재고 → 옵션 값 → 옵션 그룹 5번이고 모두 in 절이다.
 * 맞춤 제작 상품이 있으면 입력 항목을 상품 아이디 in 절로 한 번 더 읽는다. 없으면 그 쿼리를 보내지 않는다.
 *
 * <p>없는 아이디는 응답에서 뺀다 — 호출한 쪽이 누락을 보고 거절한다. 숨긴 상품의 SKU 도 행을 내리고 {@code purchasable=false} 로 알린다.
 *
 * <p>판매 상태는 상세와 같은 규칙({@link SaleAvailability#ofSku})으로 실제 재고에서 낸다 — 재고 행이 없는 SKU 는 품절이다.
 * 판매 가능 수량은 응답에 싣지 않는다 (주문 생성 #12 와의 계약을 바꾸지 않는다). 수량 부족은 재고 선점이 알린다.
 *
 * <p>상품을 도메인 {@code Product} 로 읽어 {@code description_html} 큰 컬럼까지 읽는다(상품 최대 100개) — 호출 패턴이 정해지는 #12 · #13 에서
 * 필요한 컬럼만 읽는 투영 쿼리로 바꾼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkuInternalProcessor {

    private static final String OPTION_NAME_DELIMITER = " / ";
    private static final Comparator<ResolvedOption> GROUP_ORDER = Comparator
        .comparingInt((ResolvedOption option) -> option.group().displayOrder())
        .thenComparingLong(option -> option.group().id())
        .thenComparingInt(option -> option.value().displayOrder())
        .thenComparingLong(option -> option.value().id());

    private final SkuRepositoryPort skuRepositoryPort;
    private final ProductRepositoryPort productRepositoryPort;
    private final StockRepositoryPort stockRepositoryPort;
    private final ProductOptionRepositoryPort productOptionRepositoryPort;
    private final ProductCustomOrderFieldRepositoryPort productCustomOrderFieldRepositoryPort;
    private final StoreProperties storeProperties;

    /**
     * @param skuIds 요청 순서 그대로. 중복은 첫 등장 순서로 한 번만 센다
     * @return 요청 순서(중복 제거 후). 없는 SKU 는 빠진다
     */
    public List<SkuInternalInfo> getSkus(List<Long> skuIds) {
        List<Long> distinctIds = skuIds.stream().distinct().toList();
        List<Sku> skus = skuRepositoryPort.findAllByIds(distinctIds);
        if (skus.isEmpty()) {
            return List.of();
        }

        Map<Long, Sku> skusById = toMap(skus, Sku::id);
        List<Product> products = productRepositoryPort.findAllByIdsIncludingHidden(distinct(skus, sku -> List.of(sku.productId())));
        Map<Long, Product> productsById = toMap(products, Product::id);
        // 상품이 없는 SKU 는 어차피 응답에서 빠지므로 그 SKU 의 옵션 값은 읽지 않는다
        List<Sku> skusWithProduct = skus.stream().filter(sku -> productsById.containsKey(sku.productId())).toList();
        SkuStocks stocks = SkuStocks.of(stockRepositoryPort.findAllBySkuIds(skusWithProduct.stream().map(Sku::id).toList()));
        List<OptionValue> values = productOptionRepositoryPort.findValuesByIds(distinct(skusWithProduct, sku -> sku.optionKey().valueIds()));
        Map<Long, OptionValue> valuesById = toMap(values, OptionValue::id);
        List<OptionGroup> groups = productOptionRepositoryPort.findGroupsByIds(distinct(values, value -> List.of(value.optionGroupId())));
        Map<Long, OptionGroup> groupsById = toMap(groups, OptionGroup::id);
        Map<Long, List<CustomOrderFieldDefinition>> fieldsByProductId = loadCustomOrderFields(products);

        List<SkuInternalInfo> infos = new ArrayList<>();
        for (Long skuId : distinctIds) {
            Sku sku = skusById.get(skuId);
            if (sku == null) {
                continue;
            }
            Product product = productsById.get(sku.productId());
            if (product == null) {
                // 상품이 없는 SKU 는 데이터 오류다. 가격을 계산할 수 없어 없는 SKU 와 같이 응답에서 뺀다 — 호출한 쪽이 누락으로 거절한다
                log.warn("sku product missing. skuId={}, productId={}", sku.id(), sku.productId());
                continue;
            }
            infos.add(toInfo(sku, product, stocks.snapshotOf(sku.id()), resolveOptions(sku, valuesById, groupsById), fieldsByProductId));
        }
        return infos;
    }

    /** 맞춤 제작이 켜진 상품만. 상품 아이디로 한 번 읽고 SKU 마다 읽지 않는다. */
    private Map<Long, List<CustomOrderFieldDefinition>> loadCustomOrderFields(List<Product> products) {
        if (!storeProperties.features().customOrder()) {
            return Map.of();
        }
        Set<Long> productIds = products.stream().filter(Product::customOrder).map(Product::id).collect(Collectors.toCollection(LinkedHashSet::new));
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CustomOrderFieldDefinition>> fieldsByProductId = new LinkedHashMap<>();
        for (CustomOrderField field : productCustomOrderFieldRepositoryPort.findAllByProductIds(productIds)) {
            fieldsByProductId.computeIfAbsent(field.productId(), id -> new ArrayList<>()).add(toFieldDefinition(field));
        }
        return fieldsByProductId;
    }

    private static CustomOrderFieldDefinition toFieldDefinition(CustomOrderField field) {
        return new CustomOrderFieldDefinition(
            field.fieldKey(), field.inputType(), field.required(), field.minValue(), field.maxValue(), field.stepValue());
    }

    private SkuInternalInfo toInfo(
        Sku sku, Product product, StockSnapshot stock, List<ResolvedOption> options, Map<Long, List<CustomOrderFieldDefinition>> fieldsByProductId
    ) {
        SaleAvailability saleStatus = SaleAvailability.ofSku(product.saleStatus(), sku.saleStatus(), stock);
        boolean customOrder = storeProperties.features().customOrder() && product.customOrder();
        return SkuInternalInfo.builder()
            .skuId(sku.id())
            .productId(product.id())
            .productName(product.name())
            .optionValues(options.stream().map(option -> new SkuOptionValueInfo(option.group().name(), option.value().name())).toList())
            .optionName(options.stream().map(option -> option.value().name()).collect(Collectors.joining(OPTION_NAME_DELIMITER)))
            .salePrice(ProductPricing.skuSalePrice(product.salePrice(), sku.additionalPrice()))
            .additionalPrice(sku.additionalPrice())
            .saleStatus(saleStatus)
            .purchasable(SaleAvailability.isPurchasable(product, sku, stock))
            .maxQuantityPerOrder(product.maxQuantityPerOrder())
            .customOrder(customOrder)
            .customOrderFields(customOrder ? fieldsByProductId.getOrDefault(product.id(), List.of()) : List.of())
            .weightGrams(sku.weightGrams())
            .hsCode(sku.hsCode())
            .build();
    }

    /**
     * 조합 키의 옵션 값을 이름과 함께 옵션 그룹의 (노출 순서, 아이디) 순으로 놓는다.
     * 값이나 그룹이 없으면(데이터 오류) 그 칸만 빼고 경고를 남긴다 — 표시명이 짧아질 뿐 가격 · 판매 상태는 맞다.
     */
    private static List<ResolvedOption> resolveOptions(Sku sku, Map<Long, OptionValue> valuesById, Map<Long, OptionGroup> groupsById) {
        List<ResolvedOption> options = new ArrayList<>();
        for (Long valueId : sku.optionKey().valueIds()) {
            OptionValue value = valuesById.get(valueId);
            if (value == null) {
                log.warn("sku option value missing. skuId={}, optionValueId={}", sku.id(), valueId);
                continue;
            }
            OptionGroup group = groupsById.get(value.optionGroupId());
            if (group == null) {
                log.warn("sku option group missing. skuId={}, optionValueId={}, optionGroupId={}", sku.id(), valueId, value.optionGroupId());
                continue;
            }
            options.add(new ResolvedOption(group, value));
        }
        options.sort(GROUP_ORDER);
        return options;
    }

    private static <T> Map<Long, T> toMap(Collection<T> elements, Function<T, Long> idOf) {
        return elements.stream().collect(Collectors.toMap(idOf, Function.identity()));
    }

    private static <T> Set<Long> distinct(Collection<T> elements, Function<T, Collection<Long>> idsOf) {
        return elements.stream().map(idsOf).flatMap(Collection::stream).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private record ResolvedOption(OptionGroup group, OptionValue value) {
    }
}
