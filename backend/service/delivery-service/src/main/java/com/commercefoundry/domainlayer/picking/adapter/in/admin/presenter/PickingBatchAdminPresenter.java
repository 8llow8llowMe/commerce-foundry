package com.commercefoundry.domainlayer.picking.adapter.in.admin.presenter;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.application.info.PickingBatchInfo;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import com.commercefoundry.domainlayer.picking.domain.model.PickingExclusion;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PickingBatchAdminPresenter {

    public PickingBatchAdminResponse present(PickingBatchInfo info) {
        PickingBatch batch = info.batch();
        return new PickingBatchAdminResponse(
            Long.toString(batch.id()),
            batch.version(),
            batch.inspectionComplete(),
            batch.totalQuantity(),
            quantities(batch.pickingQuantities()),
            batch.orders().stream().sorted(Comparator.comparingLong(PickingSnapshot::orderId))
                .map(order -> new PickingBatchAdminResponse.Order(Long.toString(order.orderId()), order.status(), order.countryCode(),
                    batch.checked(order.orderId()), quantities(order.quantities()))).toList(),
            batch.exclusions().stream().sorted(Comparator.comparingLong(PickingExclusion::orderId))
                .map(this::exclusion).toList(),
            batch.checks().stream().map(this::check).toList()
        );
    }

    public byte[] pickingListCsv(PickingBatchInfo info) {
        PickingBatch batch = info.batch();
        StringBuilder csv = new StringBuilder("\uFEFF구분,출고 묶음,주문,상품 옵션,꺼낼 수량\r\n");
        for (var quantity : quantities(batch.pickingQuantities())) {
            appendRow(csv, "합계", batch.id(), "", quantity);
        }
        for (PickingSnapshot order : batch.orders().stream().sorted(Comparator.comparingLong(PickingSnapshot::orderId)).toList()) {
            for (var quantity : quantities(order.quantities())) {
                appendRow(csv, "주문별", batch.id(), "#" + order.orderId(), quantity);
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // 행 구분은 고정 문자열이고 동적 값은 양의 정수다. ID에 붙인 #은 엑셀에서 긴 숫자의 반올림을 막는다.
    private void appendRow(StringBuilder csv, String kind, long batchId, String orderId, PickingBatchAdminResponse.Quantity quantity) {
        csv.append(kind).append(",#").append(batchId).append(',').append(orderId)
            .append(",#").append(quantity.skuId()).append(',').append(quantity.quantity()).append("\r\n");
    }

    private List<PickingBatchAdminResponse.Quantity> quantities(Map<Long, Long> quantities) {
        return quantities.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> new PickingBatchAdminResponse.Quantity(Long.toString(entry.getKey()), entry.getValue())).toList();
    }

    private PickingBatchAdminResponse.Exclusion exclusion(PickingExclusion exclusion) {
        PickingExclusion.Reason reason = exclusion.reason();
        return new PickingBatchAdminResponse.Exclusion(Long.toString(exclusion.orderId()),
            CodeNameDescriptionMetadata.of(reason.name(), reason.displayName(), reason.displayName()));
    }

    private PickingBatchAdminResponse.Check check(PickingCheck check) {
        return new PickingBatchAdminResponse.Check(Long.toString(check.orderId()), check.successful(),
            reason(check.failure()), submitted(check.quantitiesKey()));
    }

    private CodeNameDescriptionMetadata reason(PickingCheck.Failure failure) {
        String name = switch (failure) {
            case NONE -> "검수 성공";
            case ORDER_NOT_IN_BATCH -> "출고 묶음에 없는 주문";
            case INVALID_QUANTITY -> "잘못된 수량";
            case WRONG_SKU -> "주문에 없는 상품 옵션";
            case SHORTAGE -> "수량 부족";
            case EXCESS -> "수량 초과";
            case ALREADY_CHECKED -> "이미 검수된 주문";
        };
        return CodeNameDescriptionMetadata.of(failure.name(), name, name);
    }

    private List<PickingBatchAdminResponse.Quantity> submitted(String key) {
        if (key.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(key.split(",")).map(value -> value.split(":", 2))
            .map(pair -> new PickingBatchAdminResponse.Quantity(pair[0], Long.parseLong(pair[1]))).toList();
    }
}
