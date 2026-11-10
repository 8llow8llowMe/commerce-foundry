package com.commercefoundry.domainlayer.order.domain.model;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** 운영자 다시 시도의 도메인 규칙 (#17 A2b) — 종결 실패 다시 열기와 업무 언어 처리 상황. 테스트 값은 모두 가짜다. */
class ClaimRecoverySituationTest {
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00.123456789Z");
    private static final List<ClaimItem> ITEMS = List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.OTHER));

    private static Claim cancellation(Claim.Status status, String failure, int attempts) {
        return new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, status, krw(1_000), 77L, attempts,
            status.isAwaitingAttempt() ? NOW : null, failure, ITEMS, NOW, 4L);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"REFUND_FAILED, REQUESTED", "RESTORATION_FAILED, REFUNDED"})
    @DisplayName("다시 열기는 종결 실패를 그 단계의 대기 상태로 돌리고 횟수 0 · 실패 코드 없음 · 다음 시각 lease 로 둔다. 금액 · 결제 · 키는 그대로다")
    void reopenReturnsToAwaitingStepOfFailure(Claim.Status failed, Claim.Status awaiting) {
        Claim source = cancellation(failed, Claim.REFUND_UNCONFIRMED, 8);

        Claim reopened = source.reopen(NOW.plusSeconds(120));

        assertThat(reopened.status()).isEqualTo(awaiting);
        assertThat(reopened.attemptCount()).isZero();
        assertThat(reopened.failureCode()).isNull();
        assertThat(reopened.nextAttemptAt()).isEqualTo(Instant.parse("2026-11-10T00:02:00.123456Z"));
        assertThat(reopened.hasSameRefundRequest(source)).isTrue();
        assertThat(reopened.version()).isEqualTo(source.version());
    }

    @ParameterizedTest
    @EnumSource(value = Claim.Status.class, names = {"REQUESTED", "REFUNDED", "COMPLETED"})
    @DisplayName("종결 실패가 아니면 다시 열지 않는다")
    void reopenRejectsNonFailedClaim(Claim.Status status) {
        assertThatThrownBy(() -> cancellation(status, null, 0).reopen(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("저장 전 클레임 · 반품은 다시 열지 않는다")
    void reopenRequiresStoredCancellation() {
        Claim unsaved = new Claim(2, 1, UUID.randomUUID(), Claim.Type.CANCELLATION, Claim.Status.REFUND_FAILED, krw(1_000), 77L, 0, null, "PAYMENT_016",
            ITEMS, null, null);
        Claim returned = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);

        assertThatThrownBy(() -> unsaved.reopen(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> returned.reopen(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "{0} · {1} → {2}")
    @CsvSource(nullValues = "null", value = {
        "REQUESTED,          null,                    AUTO_PROCESSING",
        "REFUNDED,           null,                    AUTO_PROCESSING",
        "COMPLETED,          null,                    NO_ACTION",
        "REFUND_FAILED,      REFUND_UNCONFIRMED,      REFUND_UNCONFIRMED",
        "REFUND_FAILED,      PAYMENT_016,             REFUND_REJECTED",
        "REFUND_FAILED,      PAYMENT_019,             REFUND_REJECTED",
        "RESTORATION_FAILED, RESTORATION_UNCONFIRMED, RESTORATION_UNCONFIRMED",
        "RESTORATION_FAILED, PRODUCT_028,             RESTORATION_REJECTED",
        "RESTORATION_FAILED, RESTORATION_DATA_INVALID, RESTORATION_REJECTED",
        "RESTORATION_FAILED, ORDER_STATUS_UNEXPECTED, ORDER_STATUS_MISMATCH"
    })
    @DisplayName("처리 상황은 상태 · 실패 코드를 운영자 언어로 옮긴다 — 원시 코드는 내지 않는다")
    void situationTranslatesStatusAndFailure(Claim.Status status, String failure, ClaimRecoverySituation expected) {
        assertThat(ClaimRecoverySituation.of(cancellation(status, failure, 0))).isEqualTo(expected);
    }

    @Test
    @DisplayName("반품 접수는 결제 후 취소 수습 대상이 아니라 조치 필요 없음이다")
    void returnHasNoRecoverySituation() {
        Claim returned = new Claim(2, 1, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), NOW, 0L);

        assertThat(ClaimRecoverySituation.of(returned)).isEqualTo(ClaimRecoverySituation.NO_ACTION);
    }

    @ParameterizedTest
    @EnumSource(ClaimRecoverySituation.class)
    @DisplayName("모든 처리 상황의 이름 · 설명은 원시 오류 코드를 담지 않는다")
    void situationTextHasNoRawCode(ClaimRecoverySituation situation) {
        assertThat(situation.getDisplayName() + situation.getDescription()).doesNotContainPattern("[A-Z]+_[0-9]{3}|UNCONFIRMED|UNEXPECTED");
    }
}
