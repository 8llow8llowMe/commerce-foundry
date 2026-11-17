package com.commercefoundry.domainlayer.picking.application.mapper;

import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingBatchEntity;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingCheckEntity;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingItemEntity;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingOrderEntity;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import com.commercefoundry.domainlayer.picking.domain.model.PickingExclusion;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 출고 묶음 domain ↔ 엔티티. 한 묶음이 네 테이블에 나뉘어 있어 MapStruct 자동 매핑 대신 직접 옮긴다. 자식 행 아이디(Snowflake)는 어댑터가 정해 넘긴다.
 * 저장된 대상 주문의 항목은 제작 상품이 아니다(제작 상품 주문은 제외로 저장된다) — 복원 때 {@code customOrder=false} 다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PickingMapper {

    default PickingBatchEntity toEntity(PickingBatch batch) {
        return PickingBatchEntity.builder().id(batch.id()).checkCount(batch.checks().size()).version(batch.version()).build();
    }

    default PickingOrderEntity toTargetEntity(long id, long batchId, PickingSnapshot order) {
        return PickingOrderEntity.builder().id(id).batchId(batchId).orderId(order.orderId())
            .orderStatus(order.status()).countryCode(order.countryCode()).build();
    }

    default PickingOrderEntity toExclusionEntity(long id, long batchId, PickingExclusion exclusion) {
        return PickingOrderEntity.builder().id(id).batchId(batchId).orderId(exclusion.orderId()).exclusionReason(exclusion.reason()).build();
    }

    default PickingItemEntity toItemEntity(long id, long batchId, long orderId, long skuId, long quantity) {
        return PickingItemEntity.builder().id(id).batchId(batchId).orderId(orderId).skuId(skuId).quantity(quantity).build();
    }

    /** @param attemptNo 묶음 안 순번 (1부터) */
    default PickingCheckEntity toCheckEntity(long id, long batchId, int attemptNo, PickingCheck check) {
        return PickingCheckEntity.builder().id(id).batchId(batchId).attemptNo(attemptNo).orderId(check.orderId())
            .quantitiesKey(check.quantitiesKey()).failure(check.failure()).build();
    }

    default PickingBatch toDomain(PickingBatchEntity batch, List<PickingOrderEntity> orders, List<PickingItemEntity> items, List<PickingCheckEntity> checks) {
        Map<Long, List<PickingSnapshot.Line>> linesByOrderId = new LinkedHashMap<>();
        items.forEach(item -> linesByOrderId.computeIfAbsent(item.getOrderId(), ignored -> new ArrayList<>())
            .add(new PickingSnapshot.Line(item.getSkuId(), item.getQuantity(), false)));
        List<PickingSnapshot> targets = orders.stream().filter(order -> order.getExclusionReason() == null)
            .map(order -> new PickingSnapshot(order.getOrderId(), order.getOrderStatus(), order.getCountryCode(),
                linesByOrderId.getOrDefault(order.getOrderId(), List.of())))
            .toList();
        List<PickingExclusion> exclusions = orders.stream().filter(order -> order.getExclusionReason() != null)
            .map(order -> new PickingExclusion(order.getOrderId(), order.getExclusionReason()))
            .toList();
        return new PickingBatch(batch.getId(), targets, exclusions,
            checks.stream().map(check -> new PickingCheck(check.getOrderId(), check.getQuantitiesKey(), check.getFailure())).toList(), batch.getVersion());
    }
}
