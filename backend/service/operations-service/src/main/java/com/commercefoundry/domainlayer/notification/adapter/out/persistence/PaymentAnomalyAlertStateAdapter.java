package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.PaymentAnomalyAlertStateEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.PaymentAnomalyAlertStateRepository;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertStatePort;
import com.commercefoundry.domainlayer.notification.domain.model.PaymentAnomalyPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentAnomalyAlertStateAdapter implements PaymentAnomalyAlertStatePort {
    private static final List<PaymentAnomalyPolicy.Family> FAMILIES = Arrays.stream(PaymentAnomalyPolicy.Family.values())
        .sorted(Comparator.comparing(Enum::name)).toList();
    private final PaymentAnomalyAlertStateRepository repository;
    private final EntityManager entityManager;

    @Override
    public Optional<PaymentAnomalyPolicy.State> findLocked() {
        var rows = repository.findAllLocked();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() != FAMILIES.size()) {
            throw new IllegalStateException("Incomplete anomaly state");
        }
        var first = rows.getFirst();
        var incidents = new EnumMap<PaymentAnomalyPolicy.Family, PaymentAnomalyPolicy.Incident>(PaymentAnomalyPolicy.Family.class);
        for (int i = 0; i < FAMILIES.size(); i++) {
            var row = rows.get(i);
            if (!FAMILIES.get(i).name().equals(row.getFamily()) || !first.getLastObservedAt().equals(row.getLastObservedAt())
                || !first.getLastAcceptedLocalAt().equals(row.getLastAcceptedLocalAt())) {
                throw new IllegalStateException("Inconsistent anomaly state");
            }
            incidents.put(FAMILIES.get(i), row.incident());
        }
        return Optional.of(new PaymentAnomalyPolicy.State(first.getLastObservedAt(), first.getLastAcceptedLocalAt(), incidents));
    }

    @Override
    public void insertInitial(PaymentAnomalyPolicy.State state) {
        // 빈 조회 뒤 경쟁자가 commit해도 신규 version=null INSERT를 끝까지 유지한다. 재조회/upsert하지 않는다.
        repository.saveAllAndFlush(FAMILIES.stream().map(family -> new PaymentAnomalyAlertStateEntity(family, state)).toList());
    }

    @Override
    public void updateLocked(PaymentAnomalyPolicy.State state) {
        var rows = FAMILIES.stream().map(family -> entityManager.getReference(PaymentAnomalyAlertStateEntity.class, family.name())).toList();
        var persistence = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        // getReference는 잠금 증거가 아니다. 필드 접근 전에 같은 PC의 로딩/관리/쓰기 잠금 전체를 검사한다.
        for (var row : rows) {
            if (!entityManager.contains(row) || !persistence.isLoaded(row) || entityManager.getLockMode(row) != LockModeType.PESSIMISTIC_WRITE) {
                throw new IllegalStateException("Anomaly state must be loaded and locked in this transaction");
            }
        }
        rows.forEach(row -> row.accept(state));
        entityManager.flush();
    }
}
