package com.commercefoundry.domainlayer.picking.application.service;

import com.commercefoundry.domainlayer.adminaudit.application.service.processor.DeliveryAdminAuditProcessor;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBulkCheckAdminResponse;
import com.commercefoundry.domainlayer.picking.adapter.in.admin.presenter.PickingBatchAdminPresenter;
import com.commercefoundry.domainlayer.picking.application.command.PickingOrderCheckCommand;
import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.info.PickingBatchInfo;
import com.commercefoundry.domainlayer.picking.application.info.PickingBulkCheckInfo;
import com.commercefoundry.domainlayer.picking.application.port.in.PickingBatchAdminUseCase;
import com.commercefoundry.domainlayer.picking.application.service.processor.OrderPickingSnapshotProcessor;
import com.commercefoundry.domainlayer.picking.application.service.processor.PickingBatchProcessor;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * 이전 주문의 커밋이 다음 주문의 롤백에 묶이지 않게 한다.
     * 각 프록시 호출이 묶음의 현재 버전을 읽고 기존 단건 검수를 독립 트랜잭션에서 끝낸다.
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PickingBulkCheckAdminResponse checkBulk(long batchId, List<PickingOrderCheckCommand> items, long actorMemberId) {
        List<PickingBulkCheckInfo.OrderResult> results = new ArrayList<>(items.size());
        PickingBulkCheckAdminResponse response;
        try {
            validateBulkCheck(batchId, items);
            for (PickingOrderCheckCommand item : items) {
                results.add(checkBulkOrder(batchId, item));
            }
            response = presenter.presentBulkCheck(new PickingBulkCheckInfo(batchId, results));
        } catch (RuntimeException exception) {
            if (isAuditedFailure(exception)) {
                audit.pickingBulkChecked(actorMemberId, batchId, false);
            }
            throw exception;
        }
        audit.pickingBulkChecked(actorMemberId, batchId, results.stream().allMatch(PickingBulkCheckInfo.OrderResult::successful));
        return response;
    }

    private void validateBulkCheck(long batchId, List<PickingOrderCheckCommand> items) {
        if (items.isEmpty() || items.stream().map(PickingOrderCheckCommand::orderId).distinct().count() != items.size()
            || items.size() > batches.detail(batchId).orders().size()) {
            throw new PickingException(PickingErrorCode.INVALID_BULK_CHECK);
        }
    }

    private PickingBulkCheckInfo.OrderResult checkBulkOrder(long batchId, PickingOrderCheckCommand item) {
        try {
            PickingBatch batch = batches.check(batchId, item.orderId(), item.quantities());
            PickingCheck check = submittedChecks(batch, item.orderId(), item.quantities()).findFirst().orElseThrow();
            return new PickingBulkCheckInfo.Recorded(item.orderId(), check.failure());
        } catch (PickingException exception) {
            if (exception.getErrorCode() != PickingErrorCode.CONCURRENTLY_CHANGED) {
                throw exception;
            }
            return new PickingBulkCheckInfo.Conflict(item.orderId());
        }
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
        audit.pickingOrderChecked(actorMemberId, batchId, submittedChecks(batch, orderId, quantities).anyMatch(PickingCheck::successful));
        return response;
    }

    // 재시도라면 마지막 기록 대신 이 요청과 수량이 같은 기록을 사용한다.
    private Stream<PickingCheck> submittedChecks(PickingBatch batch, long orderId, Map<Long, Long> quantities) {
        String submitted = PickingCheck.key(quantities);
        return batch.checks().stream().filter(check -> check.orderId() == orderId && check.quantitiesKey().equals(submitted));
    }

    private boolean isAuditedFailure(RuntimeException exception) {
        return !(exception instanceof PickingException pickingException && pickingException.getErrorCode().getHttpStatus() == HttpStatus.BAD_REQUEST);
    }
}
