package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.catalog.domain.model.CatalogSku;
import com.commercefoundry.domainlayer.order.application.command.OrderItemCommand;
import com.commercefoundry.domainlayer.order.application.command.OrderQuoteCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.exception.OrderFieldValidationException;
import com.commercefoundry.domainlayer.order.application.model.ShippingFeeQuoteQuery;
import com.commercefoundry.domainlayer.order.application.port.out.ShippingFeeQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.ShippingFeeQuoteQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.ShippingFeeQuote;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 주문서 금액 계산. 주문서 미리보기와 주문 생성이 <b>같은 {@link #quote}</b> 를 쓴다 — 두 금액이 갈라지지 않게 계산 코드를 하나로 둔다 ({@code order-flow-guide.md} §2-3).
 *
 * <p>DB 를 쓰지 않아 트랜잭션을 걸지 않는다. 원격 호출은 배송비 견적(delivery)뿐이고, SKU 조회(product)는 부른 Facade 가 {@code CatalogSkuProcessor#findSkus} 로
 * 먼저 끝내 결과만 넘긴다 — 다른 컨텍스트의 Processor 를 여기 주입하지 않는다 ({@code architecture-guide.md} §3).
 *
 * <p>로그에 배송지(우편번호) · 맞춤 제작 입력 값을 남기지 않는다 ({@code coding-conventions.md} §8).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQuoteProcessor {

    /** 찾을 수 없는 SKU 의 표시 이름 — 상품명을 알 수 없고, SKU 아이디는 고객 메시지에 넣지 않는다. */
    static final String UNKNOWN_ITEM_NAME = "찾을 수 없는 상품";

    /**
     * 품절이 아닌데 구매할 수 없는 SKU(숨김 · 판매 종료 — 응답으로는 둘을 가를 수 없다)의 표시 이름. 숨긴 상품의 이름이 아이디만 넣은 요청으로
     * 새지 않게 상품명을 넣지 않는다.
     */
    static final String NOT_ON_SALE_ITEM_NAME = "판매하지 않는 상품";

    private static final String NAME_DELIMITER = ", ";

    private final ShippingFeeQueryPort shippingFeeQueryPort;

    /**
     * 주문서를 계산한다. 아래 순서로 처음 걸리는 단계 하나로 거절하고, 그 단계에 걸린 줄은 모두 모아 메시지에 싣는다(같은 이름은 한 번).
     * 줄 이름은 없는 SKU → {@value #UNKNOWN_ITEM_NAME}, 품절이 아닌데 구매할 수 없는 SKU(숨김 · 판매 종료) → {@value #NOT_ON_SALE_ITEM_NAME},
     * 그 밖(구매 가능 · 품절) → "상품명(옵션명)" 이다. 중복 줄 판정이 판매 가능 판정보다 먼저라 중복 메시지도 같은 규칙을 거친다.
     *
     * <ol>
     *   <li>같은 (SKU, 정규화한 맞춤 제작 입력) 줄이 두 번 이상 — {@code DUPLICATE_LINE}(400)</li>
     *   <li>없는 SKU · {@code purchasable=false} — {@code ITEM_NOT_PURCHASABLE}(409)</li>
     *   <li>맞춤 제작 상품이 아닌데 맞춤 제작 입력이 있다 — {@code CUSTOM_OPTION_NOT_ALLOWED}(400)</li>
     *   <li>맞춤 제작 상품이면 입력을 항목 정의와 대조한다 — 필수 누락 · 모르는 fieldKey · NUMBER 범위 · step · TEXT 길이.
     *       400 {@link OrderFieldValidationException}. {@code fieldErrors[].field} 는 {@code items[i].customOptions[j].value} 다.
     *       빠진 필수 항목은 그 줄에 있던 입력 개수 다음 칸이다. 입력값은 메시지에 넣지 않는다</li>
     *   <li><b>상품 단위</b> 수량 합이 상품의 {@code maxQuantityPerOrder}(null 이면 제한 없음)를 넘는다 — {@code QUANTITY_EXCEEDED}(409)</li>
     *   <li>판매가 통화가 섞였다 — {@code CURRENCY_MIXED}(409). 주문 통화는 SKU 판매가의 통화다</li>
     *   <li>줄 금액 = 단가 × 수량, 상품 합계 = Σ 줄 금액</li>
     *   <li>배송 국가로 국내 · 해외를 가른다({@link ShippingScope#ofCountry}, 통화는 보지 않는다). 해외면 배송 가능 국가 → 무게 · HS 미입력 → 총 무게 순이다
     *       ({@code order-flow-guide.md} §2-2) — 무게 또는 HS 가 없는 SKU 가 있으면 배송 가능 국가를 먼저 확인해 false 면 {@code DESTINATION_NOT_SUPPORTED}(400),
     *       true 면 {@code INTERNATIONAL_SHIPPING_INFO_MISSING}(409). 확인이 국가 판정 전 사유(정책 미설정 {@code SHIPPING_UNAVAILABLE} ·
     *       통화 불일치 {@code DESTINATION_CURRENCY_NOT_SUPPORTED})로 거절되면 그 코드로 답한다 — 무게 · HS 를 채워도 실제 견적이 같은 이유로 실패한다.
     *       무게 · HS 가 모두 있으면 무게 1 미만은 SKU 응답 계약 위반이라 {@code PRODUCT_SERVICE_UNAVAILABLE}(503),
     *       Σ(무게 × 수량)이 int 범위를 넘으면 견적을 부르지 않고 {@code SHIPPING_WEIGHT_NOT_SUPPORTED}(400) — 둘 다 견적 전이라 배송할 수 없는 국가(견적의 거절)보다
     *       앞선다. 국내면 무게 · HS 를 보지 않는다</li>
     *   <li>배송비 견적 — 기준 금액은 상품 합계. 국내는 우편번호를 싣고 무게는 (SKU 에 값이 있어도) 싣지 않는다. 해외는 총 무게를 싣고 우편번호는 있을 때만 싣는다</li>
     *   <li>총액 = 상품 합계 + 배송비</li>
     * </ol>
     *
     * @param skus 요청 SKU 를 한 번에 조회한 결과 ({@code CatalogSkuProcessor#findSkus}). 없는 SKU 는 키가 없다
     * @throws OrderException 위 거절, 또는 배송비 견적의 거절 · 장애 ({@link ShippingFeeQueryPort#quote})
     */
    public OrderQuote quote(OrderQuoteCommand command, Map<Long, CatalogSku> skus) {
        List<OrderItemCommand> items = command.items();
        rejectDuplicateLines(items, skus);
        rejectLines(items, skus, (item, sku) -> sku == null || !sku.purchasable(), OrderErrorCode.ITEM_NOT_PURCHASABLE);
        rejectLines(items, skus, (item, sku) -> !item.customOptions().isEmpty() && !sku.customOrder(), OrderErrorCode.CUSTOM_OPTION_NOT_ALLOWED);
        List<OrderFieldValidationException.Violation> fieldViolations = OrderCustomOptionRules.violations(items, skus);
        if (!fieldViolations.isEmpty()) {
            throw new OrderFieldValidationException(fieldViolations);
        }
        rejectQuantityOverLimit(items, skus);
        CurrencyCode currency = currencyOf(items, skus);

        List<OrderQuoteLine> lines = items.stream().map(item -> toLine(item, skus.get(item.skuId()))).toList();
        Money itemsAmount = lines.stream().map(OrderQuoteLine::lineAmount).reduce(Money.zero(currency), Money::plus);
        ShippingFeeQuote shippingFeeQuote = quoteShippingFee(lines, itemsAmount, command.countryCode(), command.postalCode());
        return new OrderQuote(currency, lines, itemsAmount, shippingFeeQuote, itemsAmount.plus(shippingFeeQuote.shippingFee()), command.countryCode());
    }

    private static void rejectDuplicateLines(List<OrderItemCommand> items, Map<Long, CatalogSku> skus) {
        Set<LineKey> seen = new HashSet<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (OrderItemCommand item : items) {
            if (!seen.add(new LineKey(item.skuId(), item.customOptions().hash()))) {
                duplicated.add(nameOf(skus.get(item.skuId())));
            }
        }
        rejectIfAny(duplicated, OrderErrorCode.DUPLICATE_LINE);
    }

    /** 조건에 걸린 줄을 모두 모아 거절한다. 조건은 SKU 가 null 일 수 있다 — 없는 SKU 판정 뒤의 조건은 SKU 가 있다고 본다. */
    private static void rejectLines(
        List<OrderItemCommand> items, Map<Long, CatalogSku> skus, BiPredicate<OrderItemCommand, CatalogSku> rejected, OrderErrorCode errorCode
    ) {
        Set<String> names = new LinkedHashSet<>();
        for (OrderItemCommand item : items) {
            CatalogSku sku = skus.get(item.skuId());
            if (rejected.test(item, sku)) {
                names.add(nameOf(sku));
            }
        }
        rejectIfAny(names, errorCode);
    }

    /** 1회 주문 최대 수량은 상품 컬럼이다 — SKU 별로 세면 S 2 + M 2 로 상한이 우회되므로 같은 상품의 줄(다른 SKU · 다른 맞춤 입력)을 모두 더한다. */
    private static void rejectQuantityOverLimit(List<OrderItemCommand> items, Map<Long, CatalogSku> skus) {
        Map<Long, ProductQuantity> products = new LinkedHashMap<>();
        for (OrderItemCommand item : items) {
            CatalogSku sku = skus.get(item.skuId());
            products.merge(sku.productId(), new ProductQuantity(sku.productName(), sku.maxQuantityPerOrder(), item.quantity()), ProductQuantity::plus);
        }
        List<String> exceeded = products.values().stream()
            .filter(ProductQuantity::exceeded)
            .map(product -> "%s(최대 %d개)".formatted(product.productName(), product.maxQuantity()))
            .toList();
        rejectIfAny(exceeded, OrderErrorCode.QUANTITY_EXCEEDED);
    }

    private static CurrencyCode currencyOf(List<OrderItemCommand> items, Map<Long, CatalogSku> skus) {
        Set<CurrencyCode> currencies = items.stream()
            .map(item -> skus.get(item.skuId()).salePrice().currency())
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(CurrencyCode.class)));
        if (currencies.size() > 1) {
            throw new OrderException(OrderErrorCode.CURRENCY_MIXED);
        }
        return currencies.iterator().next();
    }

    private static OrderQuoteLine toLine(OrderItemCommand item, CatalogSku sku) {
        return OrderQuoteLine.of(
            sku.skuId(), sku.productId(), sku.productName(), sku.optionName(),
            sku.salePrice(), item.quantity(), item.customOptions(), sku.customOrder(),
            sku.weightGrams(), sku.hsCode());
    }

    private ShippingFeeQuote quoteShippingFee(List<OrderQuoteLine> lines, Money itemsAmount, String countryCode, String postalCode) {
        // 국가 형식은 주문 요청 검증이 먼저 거른다 — 여기까지 온 형식 오류는 조립 버그다
        ShippingScope scope = ShippingScope.ofCountry(countryCode)
            .orElseThrow(() -> new IllegalArgumentException("배송 국가 코드가 ISO 3166-1 alpha-2 대문자 형식이 아닙니다"));
        if (scope == ShippingScope.DOMESTIC) {
            // 국내는 무게 · HS 를 보지 않는다 — SKU 에 값이 있어도 견적에 무게를 싣지 않는다
            return toShippingFeeQuote(shippingFeeQueryPort.quote(new ShippingFeeQuoteQuery(itemsAmount, countryCode, postalCode, null)), null);
        }
        rejectMissingInternationalShippingInfo(lines, itemsAmount, countryCode, postalCode);
        int totalWeightGrams = totalWeightGramsOf(lines);
        return toShippingFeeQuote(
            shippingFeeQueryPort.quote(new ShippingFeeQuoteQuery(itemsAmount, countryCode, postalCode, totalWeightGrams)), totalWeightGrams);
    }

    /**
     * 해외 주문에 무게 또는 HS 코드가 없는 SKU 가 있으면 거절한다 — 견적을 부르기 전이다(무게 없이 부르면 delivery 의 무게 필수 오류가 나 원인이 흐려진다).
     * 다만 배송할 수 없는 국가라는 사유가 앞선다 — 해외 판매 전에는 대부분의 SKU 가 미입력이라 순서가 바뀌면 엉뚱한 사유가 보인다 ({@code order-flow-guide.md} §2-2).
     *
     * <p>배송 가능 국가 확인({@link ShippingFeeQueryPort#isDestinationSupported})이 던지는 거절은 그대로 전파한다 — 정책 미설정({@code SHIPPING_UNAVAILABLE}) ·
     * 통화 불일치({@code DESTINATION_CURRENCY_NOT_SUPPORTED})는 delivery 가 국가를 보기 전 사유라 국가가 배송 가능하다는 뜻이 아니고, 무게 · HS 를 채워도
     * 실제 견적이 같은 이유로 실패한다. 그래서 미입력({@code INTERNATIONAL_SHIPPING_INFO_MISSING})으로 바꾸지 않는다.
     */
    private void rejectMissingInternationalShippingInfo(List<OrderQuoteLine> lines, Money itemsAmount, String countryCode, String postalCode) {
        Set<String> missing = new LinkedHashSet<>();
        for (OrderQuoteLine line : lines) {
            if (line.weightGrams() == null || line.hsCode() == null) {
                missing.add(nameOf(line.productName(), line.optionName()));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        if (!shippingFeeQueryPort.isDestinationSupported(itemsAmount, countryCode, postalCode)) {
            throw new OrderException(OrderErrorCode.DESTINATION_NOT_SUPPORTED);
        }
        throw new OrderException(OrderErrorCode.INTERNATIONAL_SHIPPING_INFO_MISSING, String.join(NAME_DELIMITER, missing));
    }

    /**
     * Σ(SKU 무게 × 수량)을 {@code long} 으로 더한다. 무게 1 미만은 product-service 의 SKU 응답 계약 위반(저장 규칙이 1 이상)이라 추측하지 않고 503 이다 —
     * 모든 줄을 본 뒤에 int 범위를 판정하므로 계약 위반이 무게 초과보다 앞선다.
     */
    private static int totalWeightGramsOf(List<OrderQuoteLine> lines) {
        long totalWeightGrams = 0L;
        for (OrderQuoteLine line : lines) {
            if (line.weightGrams() < 1) {
                log.error("product-service sku weight violates contract. skuId={}, weightGrams={}", line.skuId(), line.weightGrams());
                throw new OrderException(OrderErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
            }
            totalWeightGrams = Math.addExact(totalWeightGrams, Math.multiplyExact((long) line.weightGrams(), line.quantity()));
        }
        if (totalWeightGrams > Integer.MAX_VALUE) {
            // 견적 요청의 무게는 Integer 다. delivery 의 가장 큰 구간 초과(DELIVERY_011)를 받았을 때와 같은 응답이고, 견적을 부르지 않는다
            throw new OrderException(OrderErrorCode.SHIPPING_WEIGHT_NOT_SUPPORTED);
        }
        return (int) totalWeightGrams;
    }

    private static ShippingFeeQuote toShippingFeeQuote(ShippingFeeQuoteQueryResult result, Integer totalWeightGrams) {
        return new ShippingFeeQuote(
            result.shippingFee(), result.baseFee(), result.freeShippingApplied(), result.remoteAreaSurcharge(), result.remoteAreaName(),
            result.freeShippingThreshold(), result.shippingPolicyVersion(), result.scope(), result.shippingZoneName(), result.weightFee(),
            totalWeightGrams);
    }

    private static void rejectIfAny(Collection<String> names, OrderErrorCode errorCode) {
        if (!names.isEmpty()) {
            throw new OrderException(errorCode, String.join(NAME_DELIMITER, names));
        }
    }

    /**
     * 판정 메시지에 싣는 SKU 이름. 없는 SKU → {@value #UNKNOWN_ITEM_NAME}, 품절이 아닌데 구매할 수 없는 SKU(숨김 · 판매 종료) → {@value #NOT_ON_SALE_ITEM_NAME},
     * 그 밖(구매 가능 · 품절) → "상품명(옵션명)". 숨긴 상품도 품절이면 품절로 보이는 장바구니 조회 상태({@code order-flow-guide.md} §2-1)와 같은 경계다.
     */
    private static String nameOf(CatalogSku sku) {
        if (sku == null) {
            return UNKNOWN_ITEM_NAME;
        }
        if (!sku.purchasable() && !sku.soldOut()) {
            return NOT_ON_SALE_ITEM_NAME;
        }
        return nameOf(sku.productName(), sku.optionName());
    }

    /** 고객이 알아볼 줄 이름 — "상품명(옵션명)", 옵션이 없으면 상품명. */
    private static String nameOf(String productName, String optionName) {
        return optionName == null || optionName.isEmpty() ? productName : "%s(%s)".formatted(productName, optionName);
    }

    /** 줄 동일성 — SKU 와 정규화한 맞춤 제작 입력의 해시 ({@code order-flow-guide.md} §2-1 "줄 동일성"). */
    private record LineKey(long skuId, String customOptionHash) {
    }

    /** 한 상품의 주문 수량 합. 상한은 상품 컬럼이라 같은 상품의 SKU 는 같은 값이지만, 다르게 오면 작은 쪽(닫힌 쪽)을 쓴다. null 은 제한 없음이다. */
    private record ProductQuantity(String productName, Integer maxQuantity, int quantity) {

        ProductQuantity plus(ProductQuantity other) {
            return new ProductQuantity(productName, stricter(maxQuantity, other.maxQuantity), quantity + other.quantity);
        }

        boolean exceeded() {
            return maxQuantity != null && quantity > maxQuantity;
        }

        private static Integer stricter(Integer left, Integer right) {
            if (left == null) {
                return right;
            }
            return right == null ? left : Integer.valueOf(Math.min(left, right));
        }
    }
}
