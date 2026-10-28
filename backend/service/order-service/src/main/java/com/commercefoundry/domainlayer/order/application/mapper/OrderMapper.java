package com.commercefoundry.domainlayer.order.application.mapper;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderItemCustomOptionEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderItemEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderShippingAddressEntity;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.entity.OrderStatusHistoryEntity;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuote;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.order.domain.model.ShippingFeeQuote;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 주문 Entity ↔ Domain. 금액이 {@code Money}(금액 + 통화) 한 값에서 컬럼 두 개로 펼쳐지고 배송비 견적이 주문 행에 납작하게 들어가
 * 자동 매핑으로는 오히려 읽기 어렵다 — 변환은 아래 default 메서드로 손으로 적는다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderMapper {

    default OrderEntity toEntity(Order order) {
        OrderQuote quote = order.quote();
        ShippingFeeQuote fee = quote.shippingFeeQuote();
        return OrderEntity.builder()
            .id(order.id())
            .memberId(order.memberId())
            .status(order.status())
            .itemsAmount(quote.itemsAmount().amount()).itemsCurrency(quote.itemsAmount().currency())
            .shippingFeeAmount(fee.shippingFee().amount()).shippingFeeCurrency(fee.shippingFee().currency())
            .totalAmount(quote.totalAmount().amount()).totalCurrency(quote.totalAmount().currency())
            .shippingScope(fee.scope())
            .shippingBaseFeeAmount(fee.baseFee().amount()).shippingBaseFeeCurrency(fee.baseFee().currency())
            .shippingFreeShippingApplied(fee.freeShippingApplied())
            .shippingFreeShippingThresholdAmount(amountOf(fee.freeShippingThreshold())).shippingFreeShippingThresholdCurrency(currencyOf(fee.freeShippingThreshold()))
            .shippingRemoteAreaSurchargeAmount(fee.remoteAreaSurcharge().amount()).shippingRemoteAreaSurchargeCurrency(fee.remoteAreaSurcharge().currency())
            .shippingRemoteAreaName(fee.remoteAreaName())
            .shippingWeightFeeAmount(amountOf(fee.weightFee())).shippingWeightFeeCurrency(currencyOf(fee.weightFee()))
            .shippingZoneName(fee.shippingZoneName())
            .shippingPolicyVersion(fee.shippingPolicyVersion())
            .shippingWeightGrams(fee.totalWeightGrams())
            .stockReservationId(order.stockReservationId())
            .paymentDueAt(order.paymentDueAt())
            .version(order.version())
            .build();
    }

    default OrderShippingAddressEntity toShippingAddressEntity(Order order) {
        ShippingAddress address = order.shippingAddress();
        return OrderShippingAddressEntity.builder()
            .orderId(order.id())
            .recipientName(address.recipientName())
            .recipientPhone(address.recipientPhone())
            .countryCode(address.countryCode())
            .postalCode(address.postalCode())
            .addressLine1(address.addressLine1())
            .addressLine2(address.addressLine2())
            .city(address.city())
            .stateProvince(address.stateProvince())
            .deliveryMemo(address.deliveryMemo())
            .build();
    }

    /** 항목 행 — 주문서 줄 순서가 줄 번호(1부터)다. */
    default List<OrderItemEntity> toItemEntities(Order order) {
        List<OrderQuoteLine> lines = order.quote().lines();
        List<OrderItemEntity> entities = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            OrderQuoteLine line = lines.get(index);
            entities.add(OrderItemEntity.builder()
                .id(order.itemIds().get(index))
                .orderId(order.id())
                .lineNo(index + 1)
                .skuId(line.skuId())
                .productId(line.productId())
                .productName(line.productName())
                .optionName(line.optionName())
                .unitPriceAmount(line.unitPrice().amount()).unitPriceCurrency(line.unitPrice().currency())
                .quantity(line.quantity())
                .customOrder(line.customOrder())
                .lineAmount(line.lineAmount().amount()).lineCurrency(line.lineAmount().currency())
                .customOptionHash(line.customOptions().hash())
                .unitWeightGrams(line.weightGrams())
                .hsCode(line.hsCode())
                .build());
        }
        return entities;
    }

    /**
     * 입력 행. 표시 순서는 목록 순서다. 입력 행 아이디는 도메인이 들지 않는 저장 세부라 저장할 때 발급한다.
     *
     * @param idGenerator 입력 행 아이디 발급
     */
    default List<OrderItemCustomOptionEntity> toCustomOptionEntities(Order order, LongSupplier idGenerator) {
        List<OrderItemCustomOptionEntity> entities = new ArrayList<>();
        List<OrderQuoteLine> lines = order.quote().lines();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            List<OrderCustomOption> values = lines.get(lineIndex).customOptions().values();
            for (int index = 0; index < values.size(); index++) {
                OrderCustomOption option = values.get(index);
                entities.add(OrderItemCustomOptionEntity.builder()
                    .id(idGenerator.getAsLong())
                    .orderId(order.id())
                    .orderItemId(order.itemIds().get(lineIndex))
                    .fieldKey(option.fieldKey())
                    .fieldValue(option.value())
                    .displayOrder(index)
                    .build());
            }
        }
        return entities;
    }

    default OrderStatusHistoryEntity toEntity(OrderStatusHistory history) {
        return OrderStatusHistoryEntity.builder()
            .id(history.id())
            .orderId(history.orderId())
            .fromStatus(history.fromStatus())
            .toStatus(history.toStatus())
            .changedByType(history.changedByType())
            .changedById(history.changedById())
            .reason(history.reason())
            .build();
    }

    /**
     * 주문서를 다시 조립한다 — {@link OrderQuote} · {@link ShippingAddress} 생성자가 금액 불변식 · 배송지 규칙을 다시 본다. 한 행 안에서 볼 수 있는 규칙은
     * DB check 가 쓰는 순간 막는다. 테이블에 걸친 불변식(줄 금액 합 · 배송 국가와 범위 · 맞춤 입력과 맞춤 제작 여부)은 저장 경로가 도메인을 거치는 것으로 지킨다.
     *
     * @param items 이 주문의 항목 — 줄 번호 순
     * @param optionsByItemId 항목 아이디 → 입력 (표시 순서)
     */
    default Order toDomain(
        OrderEntity entity, OrderShippingAddressEntity address, List<OrderItemEntity> items, Map<Long, List<OrderItemCustomOptionEntity>> optionsByItemId
    ) {
        List<OrderQuoteLine> lines = items.stream().map(item -> toLine(item, optionsByItemId.getOrDefault(item.getId(), List.of()))).toList();
        ShippingFeeQuote shippingFee = new ShippingFeeQuote(
            Money.of(entity.getShippingFeeAmount(), entity.getShippingFeeCurrency()),
            Money.of(entity.getShippingBaseFeeAmount(), entity.getShippingBaseFeeCurrency()), entity.isShippingFreeShippingApplied(),
            Money.of(entity.getShippingRemoteAreaSurchargeAmount(), entity.getShippingRemoteAreaSurchargeCurrency()), entity.getShippingRemoteAreaName(),
            moneyOf(entity.getShippingFreeShippingThresholdAmount(), entity.getShippingFreeShippingThresholdCurrency()), entity.getShippingPolicyVersion(),
            entity.getShippingScope(), entity.getShippingZoneName(), moneyOf(entity.getShippingWeightFeeAmount(), entity.getShippingWeightFeeCurrency()),
            entity.getShippingWeightGrams());
        OrderQuote quote = new OrderQuote(entity.getItemsCurrency(), lines, Money.of(entity.getItemsAmount(), entity.getItemsCurrency()), shippingFee,
            Money.of(entity.getTotalAmount(), entity.getTotalCurrency()), address.getCountryCode());
        ShippingAddress shippingAddress = new ShippingAddress(address.getRecipientName(), address.getRecipientPhone(), address.getCountryCode(),
            address.getPostalCode(), address.getAddressLine1(), address.getAddressLine2(), address.getCity(), address.getStateProvince(), address.getDeliveryMemo());
        return new Order(entity.getId(), entity.getMemberId(), entity.getStatus(), quote, items.stream().map(OrderItemEntity::getId).toList(), shippingAddress,
            entity.getStockReservationId(), entity.getPaymentDueAt(), entity.getCreatedAt(), entity.getVersion());
    }

    private OrderQuoteLine toLine(OrderItemEntity item, List<OrderItemCustomOptionEntity> options) {
        OrderCustomOptions customOptions = OrderCustomOptions.of(
            options.stream().map(option -> new OrderCustomOption(option.getFieldKey(), option.getFieldValue())).toList());
        return new OrderQuoteLine(item.getSkuId(), item.getProductId(), item.getProductName(), item.getOptionName(),
            Money.of(item.getUnitPriceAmount(), item.getUnitPriceCurrency()), item.getQuantity(), Money.of(item.getLineAmount(), item.getLineCurrency()),
            customOptions, item.isCustomOrder(), item.getUnitWeightGrams(), item.getHsCode());
    }

    private static Long amountOf(Money money) {
        return money == null ? null : money.amount();
    }

    private static CurrencyCode currencyOf(Money money) {
        return money == null ? null : money.currency();
    }

    private static Money moneyOf(Long amount, CurrencyCode currency) {
        return amount == null ? null : Money.of(amount, currency);
    }
}
