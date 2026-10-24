package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.CategoryPathInfo;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderFieldInfo;
import com.commercefoundry.domainlayer.product.application.info.CustomOrderInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductDetailInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductImageInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductOptionGroupInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductOptionValueInfo;
import com.commercefoundry.domainlayer.product.application.info.ProductSkuInfo;
import com.commercefoundry.domainlayer.product.application.model.ProductSaleSummary;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import com.commercefoundry.domainlayer.product.domain.model.ImageType;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.ProductPricing;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuStocks;
import com.commercefoundry.global.properties.StoreProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 고객 상품 상세. 쿼리는 상품 → (Facade 가 부르는 카테고리 전체) → 옵션 그룹 → 옵션 값 → SKU → 재고 → 이미지 → (맞춤 제작일 때만) 입력 항목이고,
 * 재고는 그 상품 SKU 아이디의 in 절 한 번, 나머지는 상품 아이디 단건 조건이다. 나누기 · 정렬은 메모리에서 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductDetailProcessor {

    private final ProductRepositoryPort productRepositoryPort;
    private final ProductOptionRepositoryPort productOptionRepositoryPort;
    private final SkuRepositoryPort skuRepositoryPort;
    private final StockRepositoryPort stockRepositoryPort;
    private final ProductImageRepositoryPort productImageRepositoryPort;
    private final ProductCustomOrderFieldRepositoryPort productCustomOrderFieldRepositoryPort;
    private final StoreProperties storeProperties;

    /**
     * 고객에게 보이는 상품. 판매 종료 상품도 돌려준다 — 화면은 "판매 종료" 로 보인다.
     *
     * @throws ProductException {@link ProductErrorCode#NOT_FOUND_PRODUCT} — 없거나 숨긴 상품. 숨김은 존재를 드러내지 않고 404 다
     */
    public Product getVisibleProduct(long productId) {
        return productRepositoryPort.findVisibleById(productId).orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
    }

    /**
     * @param categoryPath 대표 카테고리 경로 중 메뉴에서 닿는 부분 — 처음 숨긴 카테고리에서 끊긴다 ({@code CategoryProcessor#visiblePathOf} — Facade 가 넘긴다, #164).
     *                     대표 카테고리가 없는 데이터 오류 · 최상위가 숨김이면 빈 목록
     */
    public ProductDetailInfo getDetail(Product product, List<Category> categoryPath) {
        List<OptionGroup> groups = productOptionRepositoryPort.findGroupsByProductId(product.id());
        List<OptionValue> values = productOptionRepositoryPort.findValuesByProductId(product.id());
        List<Sku> skus = skuRepositoryPort.findAllByProductId(product.id());
        SkuStocks stocks = SkuStocks.of(stockRepositoryPort.findAllBySkuIds(skus.stream().map(Sku::id).toList()));
        List<ProductImage> images = productImageRepositoryPort.findAllByProductId(product.id());

        ProductSaleSummary summary = ProductSaleSummary.ofSkus(product.saleStatus(), product.salePrice(), product.listPrice(), skus, stocks);
        Map<Long, List<OptionValue>> valuesByGroupId = values.stream()
            .collect(Collectors.groupingBy(OptionValue::optionGroupId, LinkedHashMap::new, Collectors.toList()));

        return ProductDetailInfo.builder()
            .productId(product.id())
            .name(product.name())
            .summary(product.summary())
            .categoryPath(categoryPath.stream().map(category -> new CategoryPathInfo(category.id(), category.name())).toList())
            .thumbnail(thumbnailOf(images))
            .images(images.stream().filter(image -> image.imageType() == ImageType.GALLERY).map(ProductImageInfo::from).toList())
            .descriptionHtml(product.descriptionHtml())
            .salePrice(summary.salePrice())
            .listPrice(summary.listPrice())
            .discountRate(summary.discountRate())
            .saleStatus(summary.saleStatus())
            .maxQuantityPerOrder(product.maxQuantityPerOrder())
            .optionGroups(groups.stream().map(group -> toGroupInfo(group, valuesByGroupId.getOrDefault(group.id(), List.of()))).toList())
            .skus(toSkuInfos(product, skus, stocks, optionValueRanks(groups, valuesByGroupId)))
            .customOrder(customOrderOf(product))
            .build();
    }

    /** 대표 이미지는 상품당 최대 1건이다. 이미지는 노출 순서 → 아이디 오름차순으로 읽으므로 데이터 오류로 둘 이상이면 앞의 것이다. */
    private static ProductImageInfo thumbnailOf(List<ProductImage> images) {
        return images.stream()
            .filter(image -> image.imageType() == ImageType.THUMBNAIL)
            .findFirst()
            .map(ProductImageInfo::from)
            .orElse(null);
    }

    private static ProductOptionGroupInfo toGroupInfo(OptionGroup group, List<OptionValue> values) {
        List<ProductOptionValueInfo> valueInfos = values.stream()
            .map(value -> new ProductOptionValueInfo(value.id(), value.name(), value.displayOrder()))
            .toList();
        return new ProductOptionGroupInfo(group.id(), group.name(), group.displayOrder(), valueInfos);
    }

    /**
     * 옵션 값 아이디 → 화면 순번. 그룹을 (노출 순서, 아이디) 순으로 돌며 그 안의 값을 차례로 센다 — 그룹 순이 먼저다.
     * 포트가 그룹 · 값을 모두 노출 순서 → 아이디 오름차순으로 주므로 다시 정렬하지 않는다.
     */
    private static Map<Long, Integer> optionValueRanks(List<OptionGroup> groups, Map<Long, List<OptionValue>> valuesByGroupId) {
        Map<Long, Integer> ranks = new HashMap<>();
        for (OptionGroup group : groups) {
            for (OptionValue value : valuesByGroupId.getOrDefault(group.id(), List.of())) {
                ranks.put(value.id(), ranks.size());
            }
        }
        return ranks;
    }

    private static List<ProductSkuInfo> toSkuInfos(Product product, List<Sku> skus, SkuStocks stocks, Map<Long, Integer> optionValueRanks) {
        return skus.stream()
            .map(sku -> new ProductSkuInfo(
                sku.id(),
                orderedOptionValueIds(sku, optionValueRanks),
                sku.additionalPrice(),
                ProductPricing.skuSalePrice(product.salePrice(), sku.additionalPrice()),
                SaleAvailability.ofSku(product.saleStatus(), sku.saleStatus(), stocks.snapshotOf(sku.id()))
            ))
            .toList();
    }

    /**
     * 조합 키의 값 아이디를 옵션 그룹 순으로 다시 놓는다 — 화면은 이 순서로 고른 옵션과 SKU 를 맞춘다.
     * 상품의 옵션 값 목록에 없는 아이디(데이터 오류)는 키 순서 그대로 뒤에 붙이고 경고를 남긴다. 화면이 그 SKU 를 고를 수 없을 뿐 응답은 실패시키지 않는다.
     */
    private static List<Long> orderedOptionValueIds(Sku sku, Map<Long, Integer> optionValueRanks) {
        List<Long> known = new ArrayList<>();
        List<Long> unknown = new ArrayList<>();
        for (Long valueId : sku.optionKey().valueIds()) {
            if (optionValueRanks.containsKey(valueId)) {
                known.add(valueId);
            } else {
                log.warn("sku option value missing. skuId={}, optionValueId={}", sku.id(), valueId);
                unknown.add(valueId);
            }
        }
        known.sort(Comparator.comparing(optionValueRanks::get));
        known.addAll(unknown);
        return known;
    }

    /** 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 만든다. 그 밖에는 입력 항목을 읽지 않는다. */
    private CustomOrderInfo customOrderOf(Product product) {
        if (!storeProperties.features().customOrder() || !product.customOrder()) {
            return null;
        }
        List<CustomOrderFieldInfo> fields = productCustomOrderFieldRepositoryPort.findAllByProductId(product.id()).stream()
            .map(ProductDetailProcessor::toFieldInfo)
            .toList();
        return new CustomOrderInfo(product.customOrderNotice(), product.customOrderLeadTimeDays(), fields);
    }

    private static CustomOrderFieldInfo toFieldInfo(CustomOrderField field) {
        return CustomOrderFieldInfo.builder()
            .fieldKey(field.fieldKey())
            .label(field.label())
            .inputType(field.inputType())
            .unit(field.unit())
            .required(field.required())
            .min(field.minValue())
            .max(field.maxValue())
            .step(field.stepValue())
            .helpText(field.helpText())
            .build();
    }
}
