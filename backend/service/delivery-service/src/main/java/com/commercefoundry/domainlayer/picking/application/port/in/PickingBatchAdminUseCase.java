package com.commercefoundry.domainlayer.picking.application.port.in;

import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import java.util.List;
import java.util.Map;

public interface PickingBatchAdminUseCase {
    PickingBatchAdminResponse create(List<Long> orderIds, long actorMemberId);

    PickingBatchAdminResponse detail(long batchId);

    byte[] downloadPickingList(long batchId, long actorMemberId);

    PickingBatchAdminResponse check(long batchId, long orderId, Map<Long, Long> quantities, long actorMemberId);
}
