package com.commercefoundry.domainlayer.settlement.adapter.out.persistence;

import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity.PgSettlementItemEntity;
import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.repository.PgSettlementItemRepository;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementItemRepositoryPort;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.persistence.util.ConstraintViolations;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * PG 정산 거래 저장 (#219). upsert 는 거래키로 기존 행을 IN 절로 한 번에 읽고(조각마다 한 번), 있는 행은 영속성 컨텍스트 안에서 내용을 바꾸고 없는 행만 새로 넣는다.
 * 새 행 아이디(Snowflake)는 여기서 만든다 — 행 하나하나가 업무 키(거래키)로만 다뤄지고 도메인 거래에는 아이디가 없다.
 */
@Component
@RequiredArgsConstructor
public class SettlementItemRepositoryAdapter implements SettlementItemRepositoryPort {

    private static final String TRANSACTION_KEY_CONSTRAINT = "uk_pg_settlement_item_transaction_key";

    private final PgSettlementItemRepository repository;
    private final SnowflakeIdGenerator idGenerator;

    /** 유니크 키 위반만 업무 충돌(동시 실행)이다. 그 밖의 제약 위반은 코드 오류라 원인 없이 제약 이름만 실어 다시 던진다(#173 2차 방어). */
    @Override
    public void upsertAll(Collection<PgSettlementItem> items, Instant collectedAt) {
        if (items.isEmpty()) {
            return;
        }
        Set<String> transactionKeys = items.stream().map(PgSettlementItem::transactionKey).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, PgSettlementItemEntity> existing = InClauseChunks.query(transactionKeys, repository::findAllByTransactionKeyIn).stream()
            .collect(Collectors.toMap(PgSettlementItemEntity::getTransactionKey, Function.identity()));
        List<PgSettlementItemEntity> created = new ArrayList<>();
        for (PgSettlementItem item : items) {
            PgSettlementItemEntity entity = existing.get(item.transactionKey());
            if (entity == null) {
                created.add(PgSettlementItemEntity.create(idGenerator.generateId(), item, collectedAt));
            } else {
                entity.overwrite(item, collectedAt);
            }
        }
        try {
            repository.saveAll(created);
            repository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, TRANSACTION_KEY_CONSTRAINT)) {
                throw new SettlementException(SettlementErrorCode.RECONCILE_CONFLICT);
            }
            throw new DataIntegrityViolationException("정산 거래 저장이 제약을 위반했습니다. constraint=%s".formatted(ConstraintViolations.constraintNameOf(exception)));
        }
    }

    @Override
    public Set<String> findPaymentKeysBySoldDate(LocalDate soldDate, CurrencyCode currency) {
        return Set.copyOf(repository.findPaymentKeysBySoldDate(soldDate, currency));
    }

    @Override
    public List<PgSettlementItem> findByPaymentKeys(Collection<String> paymentKeys) {
        return InClauseChunks.query(paymentKeys, repository::findAllByPaymentKeyIn).stream().map(PgSettlementItemEntity::toDomain).toList();
    }
}
