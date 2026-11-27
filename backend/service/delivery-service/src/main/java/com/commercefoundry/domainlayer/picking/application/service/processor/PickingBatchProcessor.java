package com.commercefoundry.domainlayer.picking.application.service.processor;

import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.port.out.PickingBatchRepositoryPort;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheckSubmission;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSelection;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출고 묶음 저장 · 조회 · 포장 검수. 원격 호출이 없다 — 트랜잭션은 여기만 건다.
 *
 * <p>주문 상태 · 송장 · 재고를 바꾸지 않는다. 검수에 성공해도 출고가 아니다 — 출고는 송장 등록이 한다.
 */
@Service
@RequiredArgsConstructor
public class PickingBatchProcessor {

    private final PickingBatchRepositoryPort pickingBatchRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** 판정 결과(대상 1건 이상)를 새 묶음으로 저장한다 — 제외 주문과 사유도 함께. */
    @Transactional(timeout = 5)
    public PickingBatch create(PickingSelection selection) {
        if (!selection.hasTarget()) {
            throw new PickingException(PickingErrorCode.NO_ELIGIBLE_ORDER);
        }
        return pickingBatchRepositoryPort.insert(PickingBatch.create(snowflakeIdGenerator.generateId(), selection));
    }

    @Transactional(readOnly = true, timeout = 5)
    public PickingBatch detail(long batchId) {
        return load(batchId);
    }

    /**
     * 주문 하나를 검수한다. 같은 주문 · 같은 수량의 재요청은 저장하지 않고 지금 묶음을 돌려준다.
     *
     * @throws PickingException {@code BATCH_NOT_FOUND} · {@code INVALID_CHECK} · {@code CONCURRENTLY_CHANGED}(같은 묶음의 다른 검수가 먼저 저장됐다 — 아무것도 저장하지 않았다)
     */
    @Transactional(timeout = 5)
    public PickingBatch check(long batchId, long orderId, Map<Long, Long> quantities) {
        return checkSubmission(batchId, orderId, new PickingCheckSubmission(quantities, Map.of()));
    }

    @Transactional(timeout = 5)
    public PickingBatch checkSubmission(long batchId, long orderId, PickingCheckSubmission submission) {
        PickingBatch batch = load(batchId);
        PickingBatch next;
        try {
            next = batch.checkSubmission(orderId, submission);
        } catch (IllegalArgumentException exception) {
            throw new PickingException(PickingErrorCode.INVALID_CHECK, exception);
        }
        if (next == batch) {
            return batch;
        }
        try {
            return pickingBatchRepositoryPort.addCheck(next);
        } catch (OptimisticLockingFailureException exception) {
            throw new PickingException(PickingErrorCode.CONCURRENTLY_CHANGED, exception);
        }
    }

    private PickingBatch load(long batchId) {
        return pickingBatchRepositoryPort.findById(batchId).orElseThrow(() -> new PickingException(PickingErrorCode.BATCH_NOT_FOUND));
    }
}
