package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.LowStockAlertSkuEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.LowStockAlertStateEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.LowStockAlertSkuRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.LowStockAlertStateRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.LowStockAlertStatePort;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class LowStockAlertStateAdapter implements LowStockAlertStatePort {
    /** IN 절 한 번의 SKU 수. 추적 행이 많아도 바인드 파라미터 상한에 닿지 않게 나눈다. */
    static final int CHUNK = 1000;
    private final LowStockAlertStateRepository states;
    private final LowStockAlertSkuRepository skus;
    private final EntityManager entityManager;

    @Override
    public Optional<LowStockAlertPolicy.State> findLocked() {
        return states.findLocked().map(LowStockAlertStateEntity::toDomain);
    }

    @Override
    public List<LowStockAlertPolicy.Tracked> findAllTracked() {
        // projection 으로 두 칸만 읽는다. 엔티티는 applySkuChanges 에서 바뀌는 행만 올린다.
        return skus.findAllTrackedRows().stream().map(row -> new LowStockAlertPolicy.Tracked(row.getSkuId(), row.getMissingSince())).toList();
    }

    @Override
    public void insertInitial(LowStockAlertPolicy.State state) {
        // 빈 조회 뒤 경쟁자가 commit 해도 신규 version=null INSERT 를 유지한다(PK 충돌이면 트랜잭션 전체 실패). 재조회 · upsert 하지 않는다.
        states.saveAndFlush(new LowStockAlertStateEntity(state));
    }

    @Override
    public void updateLocked(LowStockAlertPolicy.State state) {
        var row = entityManager.getReference(LowStockAlertStateEntity.class, LowStockAlertStateEntity.SOURCE);
        // getReference 는 잠금 증거가 아니다. 필드 접근 전에 같은 영속성 컨텍스트에서 로딩 · 쓰기 잠금을 확인한다.
        if (!entityManager.contains(row) || !entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(row)
            || entityManager.getLockMode(row) != LockModeType.PESSIMISTIC_WRITE) {
            throw new IllegalStateException("Low stock alert state must be loaded and locked in this transaction");
        }
        row.accept(state);
        entityManager.flush();
    }

    @Override
    public void applySkuChanges(LowStockAlertPolicy.Changes changes) {
        if (changes.isEmpty()) {
            return;
        }
        if (!changes.inserted().isEmpty()) {
            skus.saveAll(changes.inserted().stream().map(LowStockAlertSkuEntity::new).toList());
        }
        var touched = new ArrayList<Long>(changes.reappeared().size() + changes.missing().size());
        touched.addAll(changes.reappeared());
        touched.addAll(changes.missing());
        var rows = new HashMap<Long, LowStockAlertSkuEntity>();
        for (var chunk : chunks(touched)) {
            rows.putAll(skus.findAllById(chunk).stream().collect(Collectors.toMap(LowStockAlertSkuEntity::getSkuId, Function.identity())));
        }
        if (rows.size() != touched.size()) {
            throw new IllegalStateException("Low stock alert sku rows changed during the scan");
        }
        changes.reappeared().forEach(skuId -> rows.get(skuId).clearMissing());
        changes.missing().forEach(skuId -> rows.get(skuId).markMissing(changes.at()));
        for (var chunk : chunks(changes.resolved())) {
            skus.deleteAllByIdInBatch(chunk);
        }
        entityManager.flush();
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        var result = new ArrayList<List<Long>>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            result.add(ids.subList(from, Math.min(ids.size(), from + CHUNK)));
        }
        return result;
    }
}
