package com.commercefoundry.domainlayer.picking.adapter.in.admin.presenter;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.application.info.PickingBatchInfo;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PickingBatchAdminPresenter {
    public PickingBatchAdminResponse present(PickingBatchInfo info) {
        var batch = info.batch();
        return new PickingBatchAdminResponse(Long.toString(batch.id()), batch.version(), batch.inspectionComplete(), batch.totalQuantity(),
            quantities(batch.pickingQuantities()), batch.orders().stream().sorted(Comparator.comparingLong(order -> order.orderId()))
                .map(order -> new PickingBatchAdminResponse.Order(Long.toString(order.orderId()), order.status(), order.countryCode(),
                    batch.checked(order.orderId()), quantities(order.quantities()))).toList(),
            batch.checks().stream().map(check -> new PickingBatchAdminResponse.Check(Long.toString(check.orderId()), check.successful(),
                reason(check.failure()), submitted(check.quantitiesKey()))).toList());
    }

    private List<PickingBatchAdminResponse.Quantity> quantities(Map<Long, Long> quantities) {
        return quantities.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> new PickingBatchAdminResponse.Quantity(Long.toString(entry.getKey()), entry.getValue())).toList();
    }

    private CodeNameDescriptionMetadata reason(PickingCheck.Failure failure) {
        String name = switch (failure) {
            case NONE -> "검수 성공";
            case ORDER_NOT_IN_BATCH -> "batch에 없는 주문";
            case INVALID_QUANTITY -> "잘못된 수량";
            case WRONG_SKU -> "주문에 없는 SKU";
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
        return Arrays.stream(key.split(",")).map(value -> value.split(":"))
            .map(pair -> new PickingBatchAdminResponse.Quantity(pair[0], Long.parseLong(pair[1]))).toList();
    }
}
