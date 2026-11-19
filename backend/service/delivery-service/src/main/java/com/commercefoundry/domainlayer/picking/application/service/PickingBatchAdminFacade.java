package com.commercefoundry.domainlayer.picking.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.DeliveryAdminAuditProcessor;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.presenter.PickingBatchAdminPresenter;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.info.PickingBatchInfo;
import com.commercefoundry.domainlayer.picking.application.port.in.PickingBatchAdminUseCase;
import com.commercefoundry.domainlayer.picking.application.service.processor.OrderPickingSnapshotProcessor;
import com.commercefoundry.domainlayer.picking.application.service.processor.PickingBatchProcessor;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 원격 주문 조회는 트랜잭션 밖에서 하고, 묶음 저장과 검수는 기존 Processor 트랜잭션에서 처리한다. */
@Service
@RequiredArgsConstructor
public class PickingBatchAdminFacade implements PickingBatchAdminUseCase {

    private final OrderPickingSnapshotProcessor snapshots;
    private final PickingBatchProcessor batches;
    private final PickingBatchAdminPresenter presenter;
    private final DeliveryAdminAuditProcessor audit;

    @Override
    public PickingBatchAdminResponse create(List<Long> orderIds, long actorMemberId) {
        PickingBatch batch;
        PickingBatchAdminResponse response;
        try {
            batch = batches.create(snapshots.load(orderIds));
            response = presenter.present(new PickingBatchInfo(batch));
        } catch (RuntimeException exception) {
            if (isAuditedFailure(exception)) {
                audit.pickingBatchCreated(actorMemberId, null, false);
            }
            throw exception;
        }
        audit.pickingBatchCreated(actorMemberId, batch.id(), true);
        return response;
    }

    @Override
    public PickingBatchAdminResponse detail(long batchId) {
        return presenter.present(new PickingBatchInfo(batches.detail(batchId)));
    }

    @Override
    public byte[] downloadPickingList(long batchId, long actorMemberId) {
        byte[] csv;
        try {
            csv = presenter.pickingListCsv(new PickingBatchInfo(batches.detail(batchId)));
        } catch (RuntimeException exception) {
            audit.pickingListDownloaded(actorMemberId, batchId, false);
            throw exception;
        }
        audit.pickingListDownloaded(actorMemberId, batchId, true);
        return csv;
    }

    @Override
    public PickingBatchAdminResponse check(long batchId, long orderId, Map<Long, Long> quantities, long actorMemberId) {
        PickingBatch batch;
        PickingBatchAdminResponse response;
        try {
            batch = batches.check(batchId, orderId, quantities);
            response = presenter.present(new PickingBatchInfo(batch));
        } catch (RuntimeException exception) {
            if (isAuditedFailure(exception)) {
                audit.pickingOrderChecked(actorMemberId, batchId, false);
            }
            throw exception;
        }
        // 재시도라면 마지막 기록 대신 이 요청과 수량이 같은 기록의 검수 결과를 사용한다.
        String submitted = PickingCheck.key(quantities);
        boolean passed = batch.checks().stream().anyMatch(
            check -> check.orderId() == orderId && check.quantitiesKey().equals(submitted) && check.successful());
        audit.pickingOrderChecked(actorMemberId, batchId, passed);
        return response;
    }

    private boolean isAuditedFailure(RuntimeException exception) {
        return !(exception instanceof PickingException pickingException && pickingException.getErrorCode().getHttpStatus() == HttpStatus.BAD_REQUEST);
    }
}
