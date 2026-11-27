package com.commercefoundry.domainlayer.picking.application.port.in;

import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBulkCheckAdminResponse;
import com.commercefoundry.domainlayer.picking.application.command.PickingOrderCheckCommand;
import java.util.List;

public interface PickingBatchAdminUseCase {
    PickingBatchAdminResponse create(List<Long> orderIds, long actorMemberId);

    PickingBatchAdminResponse detail(long batchId);

    byte[] downloadPickingList(long batchId, long actorMemberId);

    PickingBulkCheckAdminResponse checkBulk(long batchId, List<PickingOrderCheckCommand> items, long actorMemberId);

    PickingBatchAdminResponse check(long batchId, PickingOrderCheckCommand command, long actorMemberId);
}
