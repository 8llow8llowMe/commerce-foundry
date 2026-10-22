package com.commercefoundry.domainlayer.product.adapter.in.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CategoryPathItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CustomOrderFieldItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.CustomOrderItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductImageItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductOptionGroupItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductOptionValueItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductSkuItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.item.ProductThumbnailItem;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductDetailResponse;
import com.commercefoundry.domainlayer.product.adapter.in.web.dto.response.ProductListItemResponse;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 고객 상품 응답의 칸을 허용 목록으로 고정한다 — 어드민 · 서비스 간 전용 값(버전 · 삭제 · 은퇴 · 저장 키 · 무게 · HS 코드 · 진열 카테고리 · 초기 재고)이
 * 고객 응답에 새지 않게 한다 ({@code architecture-guide.md} §2 — 고객 · 어드민 · 서비스 간 DTO 비공유, #9 PR-D).
 *
 * <p>칸을 더하면 여기서 실패한다. 고객에게 내도 되는 값인지 확인한 뒤 이 표를 같이 고친다.
 */
class ProductWebResponseComponentsTest {

    /** 고객 응답에 나오면 안 되는 이름 — 어드민 상세 · 쓰기 · 서비스 간 조회에만 있는 값이다. */
    private static final Set<String> ADMIN_OR_INTERNAL_ONLY = Set.of(
        "version", "deleted", "deletedAt", "retired", "retiredAt", "storageKey", "weightGrams", "hsCode", "categoryIds", "initialStock"
    );

    /** 상품 고객 응답 record → 허용한 칸(선언 순서). */
    private static final Map<Class<?>, List<String>> ALLOWED = Map.of(
        ProductDetailResponse.class, List.of(
            "productId", "name", "summary", "categoryPath", "thumbnail", "images", "descriptionHtml",
            "salePrice", "listPrice", "discountRate", "saleStatus", "maxQuantityPerOrder", "optionGroups", "skus", "customOrder"
        ),
        ProductListItemResponse.class, List.of(
            "productId", "name", "thumbnail", "salePrice", "listPrice", "discountRate", "hasOptionSurcharge", "saleStatus", "customOrder"
        ),
        ProductSkuItem.class, List.of("skuId", "optionValueIds", "additionalPrice", "salePrice", "saleStatus"),
        ProductOptionGroupItem.class, List.of("optionGroupId", "name", "displayOrder", "values"),
        ProductOptionValueItem.class, List.of("optionValueId", "name", "displayOrder"),
        ProductImageItem.class, List.of("imageId", "url", "width", "height", "altText", "displayOrder"),
        ProductThumbnailItem.class, List.of("imageId", "url", "width", "height", "altText"),
        CategoryPathItem.class, List.of("categoryId", "name"),
        CustomOrderItem.class, List.of("notice", "leadTimeDays", "fields"),
        CustomOrderFieldItem.class, List.of("fieldKey", "label", "inputType", "unit", "required", "min", "max", "step", "helpText")
    );

    static Stream<Arguments> customerRecords() {
        return ALLOWED.entrySet().stream()
            .sorted(Map.Entry.comparingByKey((left, right) -> left.getSimpleName().compareTo(right.getSimpleName())))
            .map(entry -> Arguments.of(entry.getKey().getSimpleName(), entry.getKey(), entry.getValue()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("customerRecords")
    @DisplayName("고객 응답 record 의 칸은 허용 목록과 정확히 같다 (순서 포함)")
    void componentsMatchAllowList(String name, Class<?> record, List<String> allowed) {
        assertThat(componentNames(record)).as(name).containsExactlyElementsOf(allowed);
        assertThat(componentNames(record)).as(name).doesNotContainAnyElementsOf(ADMIN_OR_INTERNAL_ONLY);
    }

    @Test
    @DisplayName("상세 · 목록 응답에서 닿는 상품 record 는 모두 허용 목록에 있고, 공용 record(금액 · 메타데이터)까지 어드민 · 서비스 간 전용 칸이 없다")
    void everyReachableRecordIsListed() {
        Set<Class<?>> reachable = reachableRecords(ProductDetailResponse.class, ProductListItemResponse.class);
        // 이 테스트와 같은 패키지(고객 상품 dto) 아래의 record — 공용 MoneyItem · CodeNameDescriptionMetadata 는 빼고 본다
        String productDtoPackage = ProductWebResponseComponentsTest.class.getPackageName();

        assertThat(reachable.stream().filter(type -> type.getPackageName().startsWith(productDtoPackage)))
            .as("새 상품 응답 record 는 ALLOWED 에 칸을 적어야 한다")
            .allSatisfy(type -> assertThat(ALLOWED).containsKey(type));
        assertThat(reachable).allSatisfy(type -> assertThat(componentNames(type)).as(type.getName()).doesNotContainAnyElementsOf(ADMIN_OR_INTERNAL_ONLY));
    }

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** 칸의 타입(목록이면 원소 타입)을 따라 닿는 record 전부. */
    private static Set<Class<?>> reachableRecords(Class<?>... roots) {
        Set<Class<?>> visited = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(roots));
        while (!pending.isEmpty()) {
            Class<?> record = pending.pop();
            if (!visited.add(record)) {
                continue;
            }
            for (RecordComponent component : record.getRecordComponents()) {
                recordTypesIn(component.getGenericType()).forEach(pending::push);
            }
        }
        return visited;
    }

    private static List<Class<?>> recordTypesIn(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.isRecord() ? List.of(clazz) : List.of();
        }
        if (type instanceof ParameterizedType parameterized) {
            return Arrays.stream(parameterized.getActualTypeArguments()).flatMap(argument -> recordTypesIn(argument).stream()).toList();
        }
        return List.of();
    }
}
