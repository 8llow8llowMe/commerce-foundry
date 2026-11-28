package com.commercefoundry.domainlayer.settlement.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.PaymentLedgerQueryAdapter;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentCancelEntity;
import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentLedgerQueryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentCancel;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity.PgSettlementItemEntity;
import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity.SettlementMismatchEntity;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementErrorCode;
import com.commercefoundry.domainlayer.settlement.application.exception.SettlementException;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementDayRepositoryPort;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementItemRepositoryPort;
import com.commercefoundry.domainlayer.settlement.application.port.out.SettlementMismatchRepositoryPort;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementCollectionSummary;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDayStatus;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDetection;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatch;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchStatus;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatchType;
import com.commercefoundry.domainlayer.settlement.domain.model.SettlementPaymentMethod;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * 정산 저장 어댑터 · 정산용 원장 조회 어댑터의 같은 계약을 H2 와 PostgreSQL 17 실제 질의로 본다 (#219) — 파생 쿼리 · JPQL · 유니크 키 · 버전 검사는 컴파일로 검증되지 않는다.
 * 유니크 키 위반의 409 변환 · NOWAIT 잠금은 PostgreSQL 에서만 본다(H2 는 제약 이름 판정이 다르다 — {@code coding-conventions.md} §9-1). 결제키 · 거래키는 합성 값이다.
 */
@DataJpaTest
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, SettlementItemRepositoryAdapter.class, SettlementDayRepositoryAdapter.class,
    SettlementMismatchRepositoryAdapter.class, PaymentLedgerQueryAdapter.class, PaymentMapperImpl.class, SettlementPersistenceContract.Ids.class})
@TestPropertySource(properties = {"spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"})
abstract class SettlementPersistenceContract {

    static final LocalDate SOLD_DATE = LocalDate.parse("2026-11-20");
    static final Instant NOW = Instant.parse("2026-11-21T00:00:00Z");
    /** 2026-11-20 00:00 KST ~ 2026-11-21 00:00 KST. */
    static final Instant FROM = Instant.parse("2026-11-19T15:00:00Z");
    static final Instant TO = Instant.parse("2026-11-20T15:00:00Z");

    @Autowired TestEntityManager em;
    @Autowired SettlementItemRepositoryPort itemPort;
    @Autowired SettlementDayRepositoryPort dayPort;
    @Autowired SettlementMismatchRepositoryPort mismatchPort;
    @Autowired PaymentLedgerQueryPort ledgerPort;

    @Test
    @DisplayName("거래 upsert 는 거래키로 멱등이다 — 다시 수집하면 행이 늘지 않고 내용만 바뀐다")
    void itemUpsertIsIdempotentByTransactionKey() {
        itemPort.upsertAll(List.of(item("tx-1", "tgen_contract_0001", SOLD_DATE, 129_000), item("tx-2", "tgen_contract_0001", SOLD_DATE, -29_000)), NOW);
        em.flush();
        em.clear();

        itemPort.upsertAll(List.of(item("tx-1", "tgen_contract_0001", SOLD_DATE, 100_000), item("tx-3", "tgen_contract_0002", SOLD_DATE, 5_000)),
            NOW.plusSeconds(60));
        em.flush();
        em.clear();

        List<PgSettlementItemEntity> rows = em.getEntityManager().createQuery("select i from PgSettlementItemEntity i order by i.transactionKey",
            PgSettlementItemEntity.class).getResultList();
        assertThat(rows).extracting(PgSettlementItemEntity::getTransactionKey).containsExactly("tx-1", "tx-2", "tx-3");
        assertThat(rows.getFirst().getAmount()).isEqualTo(100_000);
        assertThat(rows.getFirst().getCollectedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(rows.get(1).getCollectedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("매출일의 결제키는 통화로 거르고 중복이 없다 · 결제키 조회는 매출일과 무관하게 모두 읽는다")
    void itemQueries() {
        PgSettlementItem usd = new PgSettlementItem("tx-usd", "tgen_contract_usd", null, SOLD_DATE, null, SettlementPaymentMethod.CARD, 895, 0, 0,
            CurrencyCode.USD, false, null);
        itemPort.upsertAll(List.of(item("tx-1", "tgen_contract_0001", SOLD_DATE, 129_000), item("tx-2", "tgen_contract_0001", SOLD_DATE, -9_000),
            item("tx-3", "tgen_contract_0001", SOLD_DATE.plusDays(1), -20_000), item("tx-4", "tgen_contract_0002", SOLD_DATE.minusDays(1), 1_000), usd), NOW);
        em.flush();
        em.clear();

        assertThat(itemPort.findPaymentKeysBySoldDate(SOLD_DATE, CurrencyCode.KRW)).containsExactly("tgen_contract_0001");
        assertThat(itemPort.findByPaymentKeys(List.of("tgen_contract_0001"))).extracting(PgSettlementItem::transactionKey)
            .containsExactlyInAnyOrder("tx-1", "tx-2", "tx-3");
        assertThat(itemPort.findByPaymentKeys(List.of())).isEmpty();
    }

    @Test
    @DisplayName("매출일 요약 — 새 행은 INSERT, 잠가 읽은 행은 버전 확인 UPDATE, 옛 버전으로 저장하면 409")
    void dayInsertUpdateAndStaleVersion() {
        SettlementDay created = dayPort.save(SettlementDay.collected(11L, SOLD_DATE, summary(2, 100_000), NOW));
        em.flush();
        em.clear();

        SettlementDay locked = dayPort.lockBySoldDate(SOLD_DATE).orElseThrow();
        SettlementDay reconciled = dayPort.save(locked.reconcile(1, NOW.plusSeconds(5)));

        assertThat(created.version()).isZero();
        assertThat(reconciled.version()).isEqualTo(1L);
        assertThat(reconciled.status()).isEqualTo(SettlementDayStatus.RECONCILED);
        assertThat(dayPort.lockBySoldDate(SOLD_DATE.plusDays(1))).isEmpty();
        assertThatThrownBy(() -> dayPort.save(created.recollect(summary(0, 0), NOW))).isInstanceOfSatisfying(SettlementException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(SettlementErrorCode.RECONCILE_CONFLICT));
    }

    @Test
    @DisplayName("불일치는 (결제키, 종류)마다 한 행 — 다시 찾으면 같은 행을 바꾸고 버전이 오른다")
    void mismatchSaveAllUpdatesInPlace() {
        SettlementDetection detection = new SettlementDetection("tgen_contract_0001", 9_000_000_001L, SettlementMismatchType.AMOUNT, 129_000, 100_000);
        SettlementDetection orphan = new SettlementDetection("tgen_contract_0002", null, SettlementMismatchType.MISSING_IN_LEDGER, 0, -5_000);
        mismatchPort.saveAll(List.of(SettlementMismatch.open(21L, SOLD_DATE, detection, CurrencyCode.KRW, NOW),
            SettlementMismatch.open(22L, SOLD_DATE, orphan, CurrencyCode.KRW, NOW)));
        em.flush();
        em.clear();

        List<SettlementMismatch> loaded = mismatchPort.findByPaymentKeys(List.of("tgen_contract_0001", "tgen_contract_0002"));
        SettlementMismatch amount = loaded.stream().filter(mismatch -> mismatch.type() == SettlementMismatchType.AMOUNT).findFirst().orElseThrow();
        List<SettlementMismatch> saved = mismatchPort.saveAll(List.of(amount.autoClear(NOW.plusSeconds(60))));
        em.flush();
        em.clear();

        assertThat(saved.getFirst().version()).isEqualTo(1L);
        assertThat(em.getEntityManager().createQuery("select count(m) from SettlementMismatchEntity m", Long.class).getSingleResult()).isEqualTo(2L);
        SettlementMismatchEntity row = em.find(SettlementMismatchEntity.class, 21L);
        assertThat(row.getStatus()).isEqualTo(SettlementMismatchStatus.RESOLVED);
        assertThat(row.getResolvedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(em.find(SettlementMismatchEntity.class, 22L).getPaymentId()).isNull();
    }

    @Test
    @DisplayName("정산용 원장 — 결제키 IN(조각 나눔) · 승인 시각 [from, to) · 통화 · 취소 IN 을 한 번씩 읽는다")
    void ledgerQueries() {
        persistPayment(1, "tgen_ledger_0001", CurrencyCode.KRW, FROM);
        persistPayment(2, "tgen_ledger_0002", CurrencyCode.KRW, TO.minusNanos(1_000));
        persistPayment(3, "tgen_ledger_0003", CurrencyCode.KRW, TO);
        persistPayment(4, "tgen_ledger_0004", CurrencyCode.USD, FROM.plusSeconds(60));
        persistPayment(5, "tgen_ledger_0005", CurrencyCode.KRW, FROM.minusNanos(1_000));
        em.persist(PaymentCancelEntity.from(new PaymentCancel(31L, 9_000_000_001L, Money.of(9_000, CurrencyCode.KRW), PaymentCancel.Status.SUCCEEDED)));
        em.persist(PaymentCancelEntity.from(new PaymentCancel(32L, 9_000_000_001L, Money.of(1_000, CurrencyCode.KRW), PaymentCancel.Status.PENDING)));
        em.persist(PaymentCancelEntity.from(new PaymentCancel(33L, 9_000_000_002L, Money.of(2_000, CurrencyCode.KRW), PaymentCancel.Status.SUCCEEDED)));
        em.flush();
        em.clear();

        List<String> manyKeys = new ArrayList<>();
        for (int index = 0; index < PaymentLedgerQueryAdapter.IN_CLAUSE_CHUNK * 2 + 1; index++) {
            manyKeys.add("tgen_absent_" + index);
        }
        manyKeys.add("tgen_ledger_0003");
        manyKeys.add("tgen_ledger_0004");

        assertThat(ledgerPort.findByPaymentKeys(manyKeys)).extracting(Payment::paymentKey).containsExactlyInAnyOrder("tgen_ledger_0003", "tgen_ledger_0004");
        assertThat(ledgerPort.findApprovedBetween(FROM, TO, CurrencyCode.KRW)).extracting(Payment::paymentKey)
            .containsExactlyInAnyOrder("tgen_ledger_0001", "tgen_ledger_0002");
        assertThat(ledgerPort.findCancelsByPaymentIds(List.of(9_000_000_001L, 9_000_000_002L))).extracting(PaymentCancel::claimId)
            .containsExactlyInAnyOrder(31L, 32L, 33L);
        assertThat(ledgerPort.findByPaymentKeys(List.of())).isEmpty();
    }

    static PgSettlementItem item(String transactionKey, String paymentKey, LocalDate soldDate, long amount) {
        return new PgSettlementItem(transactionKey, paymentKey, "9000000001", soldDate, soldDate.plusDays(3), SettlementPaymentMethod.CARD, amount,
            amount / 30, amount - amount / 30, CurrencyCode.KRW, amount < 0, Instant.parse("2026-11-20T01:00:03Z"));
    }

    static SettlementCollectionSummary summary(int count, long amount) {
        return new SettlementCollectionSummary(count, amount, 0, 0, CurrencyCode.KRW);
    }

    private void persistPayment(long index, String paymentKey, CurrencyCode currency, Instant approvedAt) {
        long id = 9_000_000_000L + index;
        em.persist(PaymentEntity.builder().id(id).orderId(id + 100).memberId(42L + index).pgOrderId(Long.toString(id)).gateway(PaymentGateway.TOSS)
            .totalAmount(129_000).totalCurrency(currency).orderName("정산 계약 시험").status(PaymentStatus.DONE).activeOrderId(id + 100)
            .paymentKey(paymentKey).approvalStartedAt(approvedAt.minusSeconds(3)).method(PaymentMethod.CARD).approvedAt(approvedAt).pgStatus("DONE")
            .orderSynced(false).build());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Ids {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(1, 1);
        }
    }
}
