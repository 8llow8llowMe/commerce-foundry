package com.commercefoundry.domainlayer.payment.adapter.out.persistence;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.APPROVED_AT;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.MEMBER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_NAME;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_ID;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.PAYMENT_KEY;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.STARTED_AT;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.approval;
import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentPersistenceConflictException.Kind;
import com.commercefoundry.domainlayer.payment.application.mapper.PaymentMapperImpl;
import com.commercefoundry.domainlayer.payment.application.port.out.PaymentRepositoryPort;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAbortReason;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentInquiry;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentMethod;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.commercefoundry.global.persistence.ConstraintViolations;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 결제 시도 영속성 어댑터를 실제 PostgreSQL 에 질의해 본다 — 파생 쿼리 · 유니크 키 · check 제약 · 버전 검사는 컴파일로 검증되지 않는다
 * ({@code coding-conventions.md} §9-5). 제약 이름은 PostgreSQL 서버 오류 필드에서 읽는다({@link ConstraintViolations}).
 *
 * <p>스키마는 DDL 스냅숏을 init script 로 적용하고 {@code ddl-auto=validate} 로 엔티티와 대조한다. 컨텍스트가 뜨면 스냅숏과 매핑이 맞는 것이다.
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 * 제약 위반은 테스트 하나에 하나만 낸다 — PostgreSQL 은 첫 오류 뒤 트랜잭션을 중단한다 ({@code bootstrap-conventions.md} 리포지토리 테스트).
 */
@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.default_schema=payment",
    "spring.cloud.discovery.enabled=false",
    "eureka.client.enabled=false"
})
@Import({QuerydslConfigurer.class, PaymentServiceFeaturesConfig.class, PaymentRepositoryAdapter.class, PaymentMapperImpl.class})
class PaymentRepositoryAdapterTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/payment-schema.sql");

    private static final long OTHER_PAYMENT_ID = PAYMENT_ID + 1;
    private static final long OTHER_ORDER_ID = ORDER_ID + 1;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PaymentRepositoryPort paymentRepositoryPort;

    @Test
    @DisplayName("새 결제 시도를 저장하고 아이디 · PG 주문번호 · 활성 주문으로 다시 읽는다")
    void savesAndFinds() {
        Payment saved = paymentRepositoryPort.save(PaymentFixtures.ready());
        entityManager.clear();

        assertThat(saved.version()).isZero();
        assertThat(paymentRepositoryPort.findById(PAYMENT_ID)).contains(saved);
        assertThat(paymentRepositoryPort.findByPgOrderId(Long.toString(PAYMENT_ID))).contains(saved);
        assertThat(paymentRepositoryPort.findActiveByOrderId(ORDER_ID)).contains(saved);
        assertThat(paymentRepositoryPort.findActiveByOrderId(OTHER_ORDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("전이를 저장하면 버전이 오르고 승인 칸이 남는다 — 생성 시각은 merge 가 덮지 않는다")
    void savesTransitions() {
        Payment ready = paymentRepositoryPort.save(PaymentFixtures.ready());
        Payment inProgress = paymentRepositoryPort.save(ready.startApproval(PAYMENT_KEY, STARTED_AT));
        Payment done = paymentRepositoryPort.save(inProgress.approve(approval(krw(129_000))));
        Payment synced = paymentRepositoryPort.save(done.markOrderSynced());
        entityManager.clear();

        assertThat(synced.version()).isEqualTo(3L);
        Payment found = paymentRepositoryPort.findById(PAYMENT_ID).orElseThrow();
        assertThat(found).isEqualTo(synced);
        assertThat(found.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(found.orderSynced()).isTrue();
        assertThat(entityManager.find(PaymentEntity.class, PAYMENT_ID).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("버전 충돌: 같은 READY 를 읽은 두 요청 중 나중 저장은 VERSION 충돌이다 — 중복 승인 시작을 막는다. 원인은 싣지 않는다")
    void staleVersionIsRejected() {
        paymentRepositoryPort.save(PaymentFixtures.ready());
        entityManager.clear();
        Payment first = paymentRepositoryPort.findById(PAYMENT_ID).orElseThrow();
        Payment second = paymentRepositoryPort.findById(PAYMENT_ID).orElseThrow();
        paymentRepositoryPort.save(first.startApproval(PAYMENT_KEY, STARTED_AT));
        entityManager.clear();

        assertConflict(() -> paymentRepositoryPort.save(second.startApproval(PAYMENT_KEY, STARTED_AT)), Kind.VERSION);
    }

    @Test
    @DisplayName("같은 주문에 활성 결제 시도를 둘 만들 수 없다 (uk_payment_active_order_id → ACTIVE_SLOT)")
    void secondActivePaymentIsRejected() {
        paymentRepositoryPort.save(PaymentFixtures.ready(PAYMENT_ID, ORDER_ID));

        assertConflict(() -> paymentRepositoryPort.save(PaymentFixtures.ready(OTHER_PAYMENT_ID, ORDER_ID)), Kind.ACTIVE_SLOT);
    }

    @Test
    @DisplayName("실패 · 중단된 결제 시도는 활성 슬롯을 놓는다 — 같은 주문에 새 결제 시도를 만들 수 있다")
    void releasedSlotAllowsNewPayment() {
        Payment ready = paymentRepositoryPort.save(PaymentFixtures.ready(PAYMENT_ID, ORDER_ID));
        Payment inProgress = paymentRepositoryPort.save(ready.startApproval(PAYMENT_KEY, STARTED_AT));
        paymentRepositoryPort.save(inProgress.reject("REJECT_CARD_COMPANY", "카드사에서 거절했습니다."));
        Payment retry = paymentRepositoryPort.save(PaymentFixtures.ready(OTHER_PAYMENT_ID, ORDER_ID));
        paymentRepositoryPort.save(retry.abort(PaymentAbortReason.REPREPARED));
        Payment third = paymentRepositoryPort.save(PaymentFixtures.ready(OTHER_PAYMENT_ID + 1, ORDER_ID));
        entityManager.clear();

        assertThat(paymentRepositoryPort.findActiveByOrderId(ORDER_ID)).contains(third);
    }

    @Test
    @DisplayName("되돌린 READY 는 결제키 · 승인 시작 시각을 남긴 채 저장되고, 조회로 미승인을 확인해 버리면 슬롯을 놓는다")
    void revertedReadyIsStoredAndAbortedAfterInquiry() {
        Payment ready = paymentRepositoryPort.save(PaymentFixtures.ready(PAYMENT_ID, ORDER_ID));
        Payment inProgress = paymentRepositoryPort.save(ready.startApproval(PAYMENT_KEY, STARTED_AT));
        Payment reverted = paymentRepositoryPort.save(inProgress.revertToReady());
        entityManager.clear();

        assertThat(paymentRepositoryPort.findActiveByOrderId(ORDER_ID)).contains(reverted);
        assertThat(reverted.approvalStartedAt()).isEqualTo(STARTED_AT);

        paymentRepositoryPort.save(reverted.abortAfterInquiry(new PaymentInquiry.Failed(PAYMENT_KEY, "NOT_FOUND_PAYMENT_SESSION", null), PaymentAbortReason.REPREPARED));
        Payment retry = paymentRepositoryPort.save(PaymentFixtures.ready(OTHER_PAYMENT_ID, ORDER_ID));
        entityManager.clear();

        assertThat(paymentRepositoryPort.findActiveByOrderId(ORDER_ID)).contains(retry);
    }

    @Test
    @DisplayName("늦은 승인은 슬롯을 다시 잡지 않는다 — 같은 주문의 새 활성 시도와 함께 저장된다")
    void lateApprovalDoesNotRetakeSlot() {
        Payment ready = paymentRepositoryPort.save(PaymentFixtures.ready(PAYMENT_ID, ORDER_ID));
        Payment inProgress = paymentRepositoryPort.save(ready.startApproval(PAYMENT_KEY, STARTED_AT));
        Payment failed = paymentRepositoryPort.save(inProgress.reject("REJECT_CARD_COMPANY", null));
        Payment retry = paymentRepositoryPort.save(PaymentFixtures.ready(OTHER_PAYMENT_ID, ORDER_ID));
        Payment late = paymentRepositoryPort.save(failed.recordLateApproval(approval(krw(129_000))));
        entityManager.clear();

        assertThat(late.status()).isEqualTo(PaymentStatus.LATE_APPROVED);
        assertThat(paymentRepositoryPort.findById(PAYMENT_ID)).contains(late);
        assertThat(paymentRepositoryPort.findActiveByOrderId(ORDER_ID)).contains(retry);
    }

    @Test
    @DisplayName("PG 주문번호는 겹칠 수 없다 — 업무 충돌이 아닌 위반은 원인 없이 제약 이름만 싣는다 (uk_payment_pg_order_id)")
    void duplicatePgOrderIdIsRejected() {
        paymentRepositoryPort.save(PaymentFixtures.ready(PAYMENT_ID, ORDER_ID));
        Payment sameNumber = new Payment(
            OTHER_PAYMENT_ID, OTHER_ORDER_ID, MEMBER_ID, Long.toString(PAYMENT_ID), PaymentGateway.TOSS, krw(1_000), ORDER_NAME, PaymentStatus.READY,
            null, null, null, null, null, null, null, null, null, false, null, null, null
        );

        assertThatThrownBy(() -> paymentRepositoryPort.save(sameNumber))
            .isExactlyInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("constraint=uk_payment_pg_order_id")
            .hasNoCause();
    }

    @Test
    @DisplayName("결제키는 겹칠 수 없다 — 승인 전 행의 null 은 겹쳐도 된다 (uk_payment_payment_key → PAYMENT_KEY)")
    void duplicatePaymentKeyIsRejected() {
        Payment first = paymentRepositoryPort.save(PaymentFixtures.ready(PAYMENT_ID, ORDER_ID));
        Payment second = paymentRepositoryPort.save(PaymentFixtures.ready(OTHER_PAYMENT_ID, OTHER_ORDER_ID));
        paymentRepositoryPort.save(first.startApproval(PAYMENT_KEY, STARTED_AT));

        assertConflict(() -> paymentRepositoryPort.save(second.startApproval(PAYMENT_KEY, STARTED_AT)), Kind.PAYMENT_KEY);
    }

    @Test
    @DisplayName("제약: 금액 0 은 저장되지 않는다 (ck_payment_total_amount_positive)")
    void zeroAmountIsRejected() {
        assertViolates(base(PaymentStatus.READY).totalAmount(0L), "ck_payment_total_amount_positive");
    }

    @Test
    @DisplayName("제약: PG 주문번호 6자 미만은 저장되지 않는다 (ck_payment_pg_order_id_length)")
    void shortPgOrderIdIsRejected() {
        assertViolates(base(PaymentStatus.READY).pgOrderId("12345"), "ck_payment_pg_order_id_length");
    }

    @Test
    @DisplayName("제약: FAILED 인데 활성 슬롯을 잡고 있으면 저장되지 않는다 (ck_payment_active_order_id_status)")
    void failedHoldingSlotIsRejected() {
        assertViolates(
            base(PaymentStatus.FAILED).paymentKey(PAYMENT_KEY).approvalStartedAt(STARTED_AT).failureCode("REJECT_CARD_COMPANY"),
            "ck_payment_active_order_id_status"
        );
    }

    @Test
    @DisplayName("제약: 활성 상태인데 슬롯이 null 이면 저장되지 않는다 — 3값 논리 구멍 (ck_payment_active_order_id_status)")
    void activeStatusWithNullSlotIsRejected() {
        assertViolates(base(PaymentStatus.READY).activeOrderId(null), "ck_payment_active_order_id_status");
    }

    @Test
    @DisplayName("제약: 결제키 없이 승인 시작 시각만 있으면 저장되지 않는다 (ck_payment_approval_started_at_key)")
    void startedAtWithoutKeyIsRejected() {
        assertViolates(base(PaymentStatus.READY).approvalStartedAt(STARTED_AT), "ck_payment_approval_started_at_key");
    }

    @Test
    @DisplayName("제약: 결제키 없는 IN_PROGRESS 는 저장되지 않는다 (ck_payment_in_progress_fields)")
    void inProgressWithoutKeyIsRejected() {
        assertViolates(base(PaymentStatus.IN_PROGRESS), "ck_payment_in_progress_fields");
    }

    @Test
    @DisplayName("제약: 승인 시각 없는 DONE 은 저장되지 않는다 (ck_payment_approved_fields)")
    void doneWithoutApprovedAtIsRejected() {
        assertViolates(base(PaymentStatus.DONE).paymentKey(PAYMENT_KEY).method(PaymentMethod.CARD), "ck_payment_approved_fields");
    }

    @Test
    @DisplayName("제약: 승인 전 결제에 결제수단이 있으면 저장되지 않는다 (ck_payment_approved_fields)")
    void methodBeforeApprovalIsRejected() {
        assertViolates(base(PaymentStatus.READY).method(PaymentMethod.CARD), "ck_payment_approved_fields");
    }

    @Test
    @DisplayName("제약: 거절 코드 없는 FAILED 는 저장되지 않는다 (ck_payment_failed_fields)")
    void failedWithoutCodeIsRejected() {
        assertViolates(base(PaymentStatus.FAILED).activeOrderId(null), "ck_payment_failed_fields");
    }

    @Test
    @DisplayName("제약: READY 에 거절 코드가 있으면 저장되지 않는다 (ck_payment_failed_fields)")
    void failureCodeOnReadyIsRejected() {
        assertViolates(base(PaymentStatus.READY).failureCode("REJECT_CARD_COMPANY"), "ck_payment_failed_fields");
    }

    @Test
    @DisplayName("제약: 이유 없는 UNKNOWN 은 저장되지 않는다 (ck_payment_unknown_fields)")
    void unknownWithoutReasonIsRejected() {
        assertViolates(base(PaymentStatus.UNKNOWN).paymentKey(PAYMENT_KEY).approvalStartedAt(STARTED_AT), "ck_payment_unknown_fields");
    }

    @Test
    @DisplayName("제약: READY 에 미확정 이유가 있으면 저장되지 않는다 (ck_payment_unknown_fields)")
    void unknownReasonOnReadyIsRejected() {
        assertViolates(base(PaymentStatus.READY).unknownReason(PaymentUnknownReason.TIMEOUT), "ck_payment_unknown_fields");
    }

    @Test
    @DisplayName("제약: 사유 없는 ABORTED 는 저장되지 않는다 (ck_payment_abort_reason_status)")
    void abortedWithoutReasonIsRejected() {
        assertViolates(base(PaymentStatus.ABORTED).activeOrderId(null), "ck_payment_abort_reason_status");
    }

    @Test
    @DisplayName("제약: 종결 이유(거절 코드 · 중단 사유)가 없는 LATE_APPROVED 는 저장되지 않는다 (ck_payment_late_approved_origin)")
    void lateApprovedWithoutOriginIsRejected() {
        assertViolates(
            base(PaymentStatus.LATE_APPROVED).activeOrderId(null).paymentKey(PAYMENT_KEY).method(PaymentMethod.CARD).approvedAt(APPROVED_AT),
            "ck_payment_late_approved_origin"
        );
    }

    @Test
    @DisplayName("제약: 승인되지 않은 결제의 주문 반영 표시는 저장되지 않는다 (ck_payment_order_synced_status)")
    void orderSyncedBeforeApprovalIsRejected() {
        assertViolates(base(PaymentStatus.READY).orderSynced(true), "ck_payment_order_synced_status");
    }

    @Test
    @DisplayName("제약: 반영된 결제의 주문 반영 실패 코드는 저장되지 않는다 (ck_payment_order_sync_failure_code_status)")
    void syncFailureCodeAfterSyncIsRejected() {
        assertViolates(
            base(PaymentStatus.DONE).paymentKey(PAYMENT_KEY).method(PaymentMethod.CARD).approvedAt(APPROVED_AT)
                .orderSynced(true).orderSyncFailureCode("ORDER_030"),
            "ck_payment_order_sync_failure_code_status"
        );
    }

    @Test
    @DisplayName("UNKNOWN 이유 컬럼은 enum 값 중 가장 긴 것을 담는다")
    void longestUnknownReasonFits() {
        Payment unknown = paymentRepositoryPort.save(PaymentFixtures.inProgress().markUnknown(PaymentUnknownReason.STALE_IN_PROGRESS));
        entityManager.clear();

        assertThat(paymentRepositoryPort.findById(PAYMENT_ID)).contains(unknown);
    }

    /** 활성 READY 행 — 각 제약 테스트가 한 칸만 어긋나게 바꾼다. */
    private static PaymentEntity.PaymentEntityBuilder base(PaymentStatus status) {
        return PaymentEntity.builder()
            .id(PAYMENT_ID).orderId(ORDER_ID).memberId(MEMBER_ID).pgOrderId(Long.toString(PAYMENT_ID)).gateway(PaymentGateway.TOSS)
            .totalAmount(1_000L).totalCurrency(CurrencyCode.KRW).orderName(ORDER_NAME).status(status).activeOrderId(ORDER_ID);
    }

    /** 제약 이름을 메시지 문구가 아니라 어댑터와 같은 판정({@link ConstraintViolations#constraintNameOf})으로 본다 — 서버 필드가 있으면 그 이름이다. */
    private void assertViolates(PaymentEntity.PaymentEntityBuilder entity, String constraintName) {
        assertThatThrownBy(() -> entityManager.persistAndFlush(entity.build()))
            .satisfies(exception -> assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo(constraintName));
    }

    private static void assertConflict(ThrowingCallable save, Kind kind) {
        assertThatThrownBy(save)
            .isInstanceOfSatisfying(PaymentPersistenceConflictException.class, exception -> assertThat(exception.getKind()).isEqualTo(kind))
            .hasNoCause();
    }
}
