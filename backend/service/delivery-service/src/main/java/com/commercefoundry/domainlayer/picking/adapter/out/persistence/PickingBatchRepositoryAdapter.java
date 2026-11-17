package com.commercefoundry.domainlayer.picking.adapter.out.persistence;

import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingCheckEntity;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingItemEntity;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.entity.PickingOrderEntity;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository.PickingBatchRepository;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository.PickingCheckRepository;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository.PickingItemRepository;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.repository.PickingOrderRepository;
import com.commercefoundry.domainlayer.picking.application.mapper.PickingMapper;
import com.commercefoundry.domainlayer.picking.application.port.out.PickingBatchRepositoryPort;
import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import com.commercefoundry.persistence.util.ConstraintViolations;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 출고 묶음 영속성. 트랜잭션은 호출하는 Processor 가 건다.
 *
 * <p>검수 추가는 "묶음 행 버전 비교 갱신 → 검수 기록 INSERT" 순서다({@code coding-conventions.md} §9 — 상태 저장을 먼저, 딸린 쓰기를 뒤에). 같은 버전에서 갈라진
 * 두 검수 중 진 쪽은 버전 비교에서 멈춰 기록까지 가지 않는다. 그래도 같은 순번이 들어오면 순번 유일 키를 같은 충돌 신호로 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class PickingBatchRepositoryAdapter implements PickingBatchRepositoryPort {

    private static final String CHECK_ATTEMPT_CONSTRAINT = "uk_picking_check_batch_id_attempt_no";

    private final PickingBatchRepository pickingBatchRepository;
    private final PickingOrderRepository pickingOrderRepository;
    private final PickingItemRepository pickingItemRepository;
    private final PickingCheckRepository pickingCheckRepository;
    private final PickingMapper pickingMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** 머리 행 1번 + 자식 세 테이블을 묶음 아이디 조건으로 1번씩 — 주문 수와 무관하게 4번 읽는다. */
    @Override
    public Optional<PickingBatch> findById(long batchId) {
        return pickingBatchRepository.findById(batchId).map(batch -> pickingMapper.toDomain(batch,
            pickingOrderRepository.findAllByBatchIdOrderByOrderIdAsc(batchId),
            pickingItemRepository.findAllByBatchIdOrderByOrderIdAscSkuIdAsc(batchId),
            pickingCheckRepository.findAllByBatchIdOrderByAttemptNoAsc(batchId)));
    }

    @Override
    public PickingBatch insert(PickingBatch batch) {
        if (batch.version() != null || !batch.checks().isEmpty()) {
            throw new IllegalArgumentException("새 출고 묶음은 버전 · 검수 기록이 없다: id=" + batch.id());
        }
        Long version = pickingBatchRepository.saveAndFlush(pickingMapper.toEntity(batch)).getVersion();
        List<PickingOrderEntity> orders = new ArrayList<>();
        List<PickingItemEntity> items = new ArrayList<>();
        batch.orders().forEach(order -> {
            orders.add(pickingMapper.toTargetEntity(snowflakeIdGenerator.generateId(), batch.id(), order));
            order.quantities().forEach((skuId, quantity) ->
                items.add(pickingMapper.toItemEntity(snowflakeIdGenerator.generateId(), batch.id(), order.orderId(), skuId, quantity)));
        });
        batch.exclusions().forEach(exclusion -> orders.add(pickingMapper.toExclusionEntity(snowflakeIdGenerator.generateId(), batch.id(), exclusion)));
        pickingOrderRepository.saveAll(orders);
        pickingItemRepository.saveAllAndFlush(items);
        return new PickingBatch(batch.id(), batch.orders(), batch.exclusions(), batch.checks(), version);
    }

    @Override
    public PickingBatch addCheck(PickingBatch batch) {
        if (batch.version() == null || batch.checks().isEmpty()) {
            throw new IllegalArgumentException("읽어 온 출고 묶음에 검수 기록을 더한다: id=" + batch.id());
        }
        // merge — 읽은 버전과 다르거나 행이 없으면 ObjectOptimisticLockingFailureException. 바로 flush 해 이 호출에서 드러나게 한다
        Long version = pickingBatchRepository.saveAndFlush(pickingMapper.toEntity(batch)).getVersion();
        int attemptNo = batch.checks().size();
        PickingCheck added = batch.checks().getLast();
        PickingCheckEntity entity = pickingMapper.toCheckEntity(snowflakeIdGenerator.generateId(), batch.id(), attemptNo, added);
        try {
            pickingCheckRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            if (!ConstraintViolations.violates(exception, CHECK_ATTEMPT_CONSTRAINT)) {
                throw exception;
            }
            throw new ObjectOptimisticLockingFailureException(PickingCheckEntity.class, batch.id(), "picking check attempt already recorded", exception);
        }
        return new PickingBatch(batch.id(), batch.orders(), batch.exclusions(), batch.checks(), version);
    }
}
