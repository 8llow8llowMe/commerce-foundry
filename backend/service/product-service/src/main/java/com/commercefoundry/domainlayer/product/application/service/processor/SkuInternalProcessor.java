package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.PriceSourceSnapshotReadException;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderFieldDefinition;
import com.commercefoundry.domainlayer.product.application.info.SkuInternalInfo;
import com.commercefoundry.domainlayer.product.application.info.SkuOptionValueInfo;
import com.commercefoundry.domainlayer.product.application.port.out.PriceSourceMetricsPort;
import com.commercefoundry.domainlayer.product.application.port.out.PriceSourceSnapshotRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.SkuBarcodeQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.BaseCurrencyPriceProjection;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductPricing;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuStocks;
import com.commercefoundry.domainlayer.product.domain.model.StockSnapshot;
import com.commercefoundry.global.properties.PriceSourceProperties;
import com.commercefoundry.global.properties.StoreProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서비스 간 SKU 벌크 조회 (주문 생성 · 장바구니 가격 확인). 쿼리는 SKU → 상품(숨김 · 삭제 포함) → 재고 → 옵션 값 → 옵션 그룹 5번이고 모두 in 절이다.
 * 맞춤 제작 상품이 있으면 입력 항목을 상품 아이디 in 절로 한 번 더 읽는다. 없으면 그 쿼리를 보내지 않는다.
 *
 * <p>없는 아이디는 응답에서 뺀다 — 호출한 쪽이 누락을 보고 거절한다. 숨긴 · 삭제(soft delete)된 상품의 SKU 도 행을 내리고 {@code purchasable=false} 로 알린다.
 * 삭제 상품은 DB check 로 판매 종료라 판매 상태도 {@code STOP_SELLING} 이다. 판정은 {@link SaleAvailability#isOpenForSale} 한 곳이다 — 여기서 삭제를 따로 거르지 않는다.
 * 은퇴한 옵션 그룹 · 값도 이름을 그대로 읽는다 — 주문 · 장바구니 줄의 옵션명이 끊기지 않게 한다(은퇴 제외는 고객 상세만 한다).
 *
 * <p>판매 상태는 상세와 같은 규칙({@link SaleAvailability#ofSku})으로 실제 재고에서 낸다 — 재고 행이 없는 SKU 는 품절이다.
 * 판매 가능 수량은 응답에 싣지 않는다 (주문 생성 #12 와의 계약을 바꾸지 않는다). 수량 부족은 재고 선점이 알린다.
 *
 * <p>상품을 도메인 {@code Product} 로 읽어 {@code description_html} 큰 컬럼까지 읽는다(상품 최대 100개) — 호출 패턴이 정해지는 #12 · #13 에서
 * 필요한 컬럼만 읽는 투영 쿼리로 바꾼다.
 *
 * <p>{@code price-source.mode=PRICE_BOOK} 이면 상품 다음에 가격 원천 snapshot 을 호출자(Facade) TX 안에서 읽는다(#20 B4b) — 응답 대상 상품(숨김 · 삭제 포함)을
 * 아이디 오름차순 20개 묶음으로 나눠 묶음마다 상품 → 전체 SKU → 상품 가격행 → SKU 가격행 최대 4 SELECT(SKU 가 없으면 3)를 더한다. 각각 in 절 한 번이라
 * N+1 이 없고(요청 SKU 100개 → 상품 최대 100개 → 묶음 최대 5개), Facade 의 REPEATABLE_READ read-only TX 라 앞의 읽기와 한 시점이다.
 * 가격행 누락 · drift 등으로 해석하지 못한 상품은 그 상품의 SKU 를 모두 응답에서 뺀다 — 호출한 쪽이 누락으로 거절한다(D8). 재고 · 옵션 · 입력 항목은 남은 SKU 로만 읽는다.
 * LEGACY(기본)는 snapshot · 지표 포트를 부르지 않는다 — 쿼리 · 응답이 전과 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkuInternalProcessor {

    private static final String OPTION_NAME_DELIMITER = " / ";
    /**
     * snapshot 한 번에 읽는 상품 수. 포트 상한({@link PriceSourceSnapshotRepositoryPort#MAX_PRODUCTS} · {@link PriceSourceSnapshotRepositoryPort#MAX_SKU_ROWS})보다
     * 작게 잡는다 — 상품당 SKU 가 최대 100개(등록 PRODUCT_095 · 추가 SKU_ADMIN_010 = {@code SkuAddProcessor.MAX_SKUS})라 20개 묶음이면 SKU 가 2000행을 넘지 않는다.
     * 넘으면 ROW_LIMIT_EXCEEDED 500 이 되어 order-service 서킷 실패로 세어진다. 이 관계는 {@code SkuInternalSnapshotChunkLimitTest} 가 고정한다.
     */
    static final int SNAPSHOT_CHUNK_PRODUCTS = 20;
    private static final String PRICE_SOURCE_SKU_MISMATCH = "SKU_NOT_IN_PROJECTION";
    private static final String PRICE_SOURCE_FAILURE_MESSAGE = "서비스 간 SKU 조회 가격 원천을 사용할 수 없습니다";
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
    private final PriceSourceSnapshotRepositoryPort priceSourceSnapshotRepositoryPort;
    private final PriceSourceProperties priceSourceProperties;
    private final PriceSourceMetricsPort priceSourceMetricsPort;

    /** 출고 대상은 주문 스냅숏이 정하므로 현재 판매·노출 상태로 바코드를 제한하지 않는다. */
    @Transactional(readOnly = true, timeout = 5)
    public Map<String, Long> resolveBarcodes(List<String> barcodes) {
        return skuRepositoryPort.findSkuIdsByBarcodes(new LinkedHashSet<>(barcodes)).stream()
            .collect(Collectors.toUnmodifiableMap(SkuBarcodeQueryResult::barcode, SkuBarcodeQueryResult::skuId));
    }

    /**
     * @param skuIds 요청 순서 그대로. 중복은 첫 등장 순서로 한 번만 센다
     * @return 요청 순서(중복 제거 후). 없는 SKU 는 빠진다. PRICE_BOOK 에서 가격 원천을 해석하지 못한 상품의 SKU 도 빠진다
     * @throws IllegalStateException PRICE_BOOK 에서 snapshot 읽기 자체가 실패할 때 — 메시지 고정 · cause 없음, 전역 처리기의 500 {@code COMMON_000}
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
        PricedSource priced = priceSourceProperties.mode() == PriceSourceProperties.Mode.PRICE_BOOK
            ? priceBookSource(products, skusWithProduct)
            : new PricedSource(products, skusWithProduct);
        Map<Long, Product> pricedProductsById = toMap(priced.products(), Product::id);
        Map<Long, Sku> pricedSkusById = toMap(priced.skus(), Sku::id);
        SkuStocks stocks = SkuStocks.of(stockRepositoryPort.findAllBySkuIds(priced.skus().stream().map(Sku::id).toList()));
        List<OptionValue> values = productOptionRepositoryPort.findValuesByIds(distinct(priced.skus(), sku -> sku.optionKey().valueIds()));
        Map<Long, OptionValue> valuesById = toMap(values, OptionValue::id);
        List<OptionGroup> groups = productOptionRepositoryPort.findGroupsByIds(distinct(values, value -> List.of(value.optionGroupId())));
        Map<Long, OptionGroup> groupsById = toMap(groups, OptionGroup::id);
        Map<Long, List<CustomOrderFieldDefinition>> fieldsByProductId = loadCustomOrderFields(priced.products());

        List<SkuInternalInfo> infos = new ArrayList<>();
        for (Long skuId : distinctIds) {
            Sku sku = skusById.get(skuId);
            if (sku == null) {
                continue;
            }
            if (!productsById.containsKey(sku.productId())) {
                // 상품이 없는 SKU 는 데이터 오류다. 가격을 계산할 수 없어 없는 SKU 와 같이 응답에서 뺀다 — 호출한 쪽이 누락으로 거절한다
                log.warn("sku product missing. skuId={}, productId={}", sku.id(), sku.productId());
                continue;
            }
            Sku pricedSku = pricedSkusById.get(skuId);
            if (pricedSku == null) {
                // PRICE_BOOK 에서 가격 원천을 해석하지 못한 상품의 SKU — 정제 로그 · 지표는 priceBookSource 가 남겼다. 호출한 쪽이 누락으로 거절한다(#20 D8)
                continue;
            }
            Product product = pricedProductsById.get(pricedSku.productId());
            infos.add(toInfo(pricedSku, product, stocks.snapshotOf(pricedSku.id()), resolveOptions(pricedSku, valuesById, groupsById), fieldsByProductId));
        }
        return infos;
    }

    /**
     * PRICE_BOOK 모드 — 호출자(Facade) TX 에 참여해 같은 시점의 가격 원천을 20개 묶음으로 읽고 상품마다 해석한다. 해석한 상품은 이미 읽은 상품의 비가격 필드에
     * 투영의 기준통화 판매가 · 정가를, SKU 는 같은 시점의 투영 SKU(추가금만 가격행 값)를 쓴다.
     *
     * <p>상품별 해석 실패(가격행 누락 · drift 등)와 응답 SKU 가 투영에 없는 상품은 그 상품의 SKU 를 모두 뺀다(#20 D8). 상품 하나 때문에 500 을 내면
     * order-service 가 5xx 를 서킷 실패로 세어 다른 상품의 장바구니 · 주문까지 막기 때문이다. 묶음 구조 오류는 상품을 가를 수 없어 그 묶음의 상품을 모두 뺀다.
     * snapshot 읽기 자체의 실패(DB 오류 · 상한 초과 · 호출자 TX 조건)는 인프라 장애라 고정 내부 오류(500)다.
     */
    private PricedSource priceBookSource(List<Product> products, List<Sku> skus) {
        // 상품 아이디 → 뺀 사유 이름. 로그 · 지표에는 사유별 건수만 낸다(아이디 · 금액 없음)
        Map<Long, String> exclusions = new HashMap<>();
        Map<Long, BaseCurrencyPriceProjection> projections = readProjections(products, exclusions);
        Map<Long, Sku> projectedSkus = new HashMap<>();
        projections.values().forEach(projection -> projection.skus().forEach(sku -> projectedSkus.put(sku.id(), sku)));
        for (Sku sku : skus) {
            Sku projected = projectedSkus.get(sku.id());
            if (projected == null || projected.productId() != sku.productId()) {
                // 같은 시점이라 정상 경로에서는 없다. 해석 실패로 이미 뺀 상품이면 처음 사유를 둔다
                exclusions.putIfAbsent(sku.productId(), PRICE_SOURCE_SKU_MISMATCH);
            }
        }
        reportExclusions(exclusions);

        // 남은 상품은 모든 응답 SKU 가 자기 투영에 있다 — 투영이 반드시 있다
        List<Product> pricedProducts = products.stream()
            .filter(product -> !exclusions.containsKey(product.id()))
            .map(product -> {
                Product projected = projections.get(product.id()).product();
                return product.withPricing(projected.salePrice(), projected.listPrice());
            })
            .toList();
        List<Sku> pricedSkus = skus.stream()
            .filter(sku -> !exclusions.containsKey(sku.productId()))
            .map(sku -> projectedSkus.get(sku.id()))
            .toList();
        return new PricedSource(pricedProducts, pricedSkus);
    }

    /** 상품 아이디 오름차순 20개 묶음으로 같은 TX 안에서 읽는다 — 같은 REPEATABLE_READ 시점이라 묶음 사이에도 정합하다. 해석 실패 상품은 사유를 남긴다. */
    private Map<Long, BaseCurrencyPriceProjection> readProjections(List<Product> products, Map<Long, String> exclusions) {
        List<Long> productIds = products.stream().map(Product::id).sorted().toList();
        Map<Long, BaseCurrencyPriceProjection> projections = new HashMap<>();
        for (int from = 0; from < productIds.size(); from += SNAPSHOT_CHUNK_PRODUCTS) {
            List<Long> chunk = productIds.subList(from, Math.min(from + SNAPSHOT_CHUNK_PRODUCTS, productIds.size()));
            List<PriceSourceSnapshotResolver.Outcome> outcomes;
            try {
                outcomes = PriceSourceSnapshotResolver.resolveEach(priceSourceSnapshotRepositoryPort.readByProductIdsInCurrentTransaction(chunk));
            } catch (PriceSourceSnapshotReadException exception) {
                throw priceSourceFailure(exception.reason().name());
            } catch (PriceSourceSnapshotResolver.ResolutionException exception) {
                String reason = exception.reason().name();
                chunk.forEach(productId -> exclusions.put(productId, reason));
                continue;
            }
            for (PriceSourceSnapshotResolver.Outcome outcome : outcomes) {
                if (outcome.isResolved()) {
                    projections.put(outcome.productId(), outcome.projection());
                } else {
                    exclusions.put(outcome.productId(), outcome.failure().name());
                }
            }
        }
        return projections;
    }

    /**
     * 운영자용 정제 로그 한 줄(이 요청에서 뺀 상품 수 · 사유별 건수)과 사유별 지표. 아이디 · 금액은 남기지 않는다.
     * 요청마다 남긴다 — 같은 상품이 장바구니 조회마다 다시 세어지므로 지표는 고유 상품 수가 아니라 요청 × 상품 건수다(rate-limit 없음).
     */
    private void reportExclusions(Map<Long, String> exclusions) {
        if (exclusions.isEmpty()) {
            return;
        }
        Map<String, Integer> countsByReason = new TreeMap<>();
        exclusions.values().forEach(reason -> countsByReason.merge(reason, 1, Integer::sum));
        log.warn("price source unresolved, products excluded from internal sku response. excludedProducts={}, reasons={}", exclusions.size(), countsByReason);
        countsByReason.forEach((reason, count) -> priceSourceMetricsPort.recordExcludedProducts(reason, count));
    }

    /** 예외는 메시지 고정 · cause 없음 — 전역 처리기 로그(타입 · 스택)에도 원문이 실리지 않는다. 운영자용 로그는 사유 이름 한 줄뿐이다. */
    private static IllegalStateException priceSourceFailure(String reason) {
        log.warn("price source unavailable for internal sku lookup. reason={}", reason);
        return new IllegalStateException(PRICE_SOURCE_FAILURE_MESSAGE);
    }

    /** 응답에 쓸 가격이 반영된 상품 · SKU. LEGACY 는 읽은 값 그대로다. */
    private record PricedSource(List<Product> products, List<Sku> skus) {
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
