package com.commercefoundry.domainlayer.picking.application.service;

import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.presenter.PickingBatchAdminPresenter;
import com.commercefoundry.domainlayer.picking.application.info.PickingBatchInfo;
import com.commercefoundry.domainlayer.picking.application.port.in.PickingBatchAdminUseCase;
import com.commercefoundry.domainlayer.picking.application.service.processor.OrderPickingSnapshotProcessor;
import com.commercefoundry.domainlayer.picking.application.service.processor.PickingBatchProcessor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PickingBatchAdminFacade implements PickingBatchAdminUseCase {
    private final OrderPickingSnapshotProcessor snapshots;
    private final PickingBatchProcessor batches;
    private final PickingBatchAdminPresenter presenter;

    /** 원격 I/O를 DB 트랜잭션 밖에 두고 저장 구간만 기존 Processor 트랜잭션으로 처리한다. */
    @Override
    public PickingBatchAdminResponse create(List<Long> orderIds) {
        return presenter.present(new PickingBatchInfo(batches.create(snapshots.load(orderIds))));
    }

    @Override
    public PickingBatchAdminResponse detail(long batchId) {
        return presenter.present(new PickingBatchInfo(batches.detail(batchId)));
    }

    @Override
    public PickingBatchAdminResponse check(long batchId, long orderId, Map<Long, Long> quantities) {
        return presenter.present(new PickingBatchInfo(batches.check(batchId, orderId, quantities)));
    }
}
