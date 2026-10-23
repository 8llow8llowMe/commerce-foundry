package com.commercefoundry.domainlayer.payment.application.mapper;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_ID;
import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentUnknownReason;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.shared.commerce.Money;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentMapperTest {

    private final PaymentMapper paymentMapper = new PaymentMapperImpl();

    static Stream<Arguments> payments() {
        return Stream.of(
            Arguments.of("READY", (Supplier<Payment>) PaymentFixtures::ready),
            Arguments.of("READY(되돌림)", (Supplier<Payment>) PaymentFixtures::reverted),
            Arguments.of("IN_PROGRESS", (Supplier<Payment>) PaymentFixtures::inProgress),
            Arguments.of("DONE", (Supplier<Payment>) PaymentFixtures::done),
            Arguments.of("DONE 주문 반영 실패", (Supplier<Payment>) () -> PaymentFixtures.done().markOrderSyncFailed("ORDER_030")),
            Arguments.of("FAILED", (Supplier<Payment>) PaymentFixtures::failed),
            Arguments.of("UNKNOWN", (Supplier<Payment>) PaymentFixtures::unknown),
            Arguments.of("UNKNOWN 사람 확인", (Supplier<Payment>) () -> PaymentFixtures.unknown().flagForManualReview(PaymentUnknownReason.MANUAL_REVIEW)),
            Arguments.of("ABORTED", (Supplier<Payment>) PaymentFixtures::aborted),
            Arguments.of("LATE_APPROVED", (Supplier<Payment>) PaymentFixtures::lateApproved)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payments")
    @DisplayName("도메인 → 엔티티 → 도메인 왕복이 같은 값이다")
    void roundTrip(String name, Supplier<Payment> payment) {
        Payment original = payment.get();

        assertThat(paymentMapper.toDomain(paymentMapper.toEntity(original))).isEqualTo(original);
    }

    @Test
    @DisplayName("금액은 total_amount + total_currency 컬럼으로, 활성 슬롯은 상태에서 유도해 쓴다")
    void writesColumns() {
        PaymentEntity active = paymentMapper.toEntity(PaymentFixtures.done());
        PaymentEntity released = paymentMapper.toEntity(PaymentFixtures.lateApproved());

        assertThat(active.getTotalAmount()).isEqualTo(129_000L);
        assertThat(active.getTotalCurrency()).isEqualTo(KRW);
        assertThat(active.getActiveOrderId()).isEqualTo(ORDER_ID);
        assertThat(active.getVersion()).as("새 결제 시도는 버전이 없다 — persist 로 간다").isNull();
        assertThat(released.getActiveOrderId()).as("늦은 승인은 슬롯을 다시 잡지 않는다").isNull();
    }

    @Test
    @DisplayName("통화가 빠진 행은 데이터 오류로 읽지 않는다")
    void missingCurrencyIsDataError() {
        PaymentEntity broken = PaymentEntity.builder()
            .id(PaymentFixtures.PAYMENT_ID).orderId(ORDER_ID).memberId(PaymentFixtures.MEMBER_ID)
            .pgOrderId(Long.toString(PaymentFixtures.PAYMENT_ID)).gateway(PaymentGateway.TOSS).totalAmount(1_000L)
            .orderName(PaymentFixtures.ORDER_NAME).status(PaymentStatus.READY).activeOrderId(ORDER_ID).version(0L)
            .build();

        assertThatThrownBy(() -> paymentMapper.toDomain(broken)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("상태와 맞지 않는 칸이 있는 행은 도메인 불변식으로 읽지 않는다")
    void inconsistentRowIsRejected() {
        PaymentEntity broken = PaymentEntity.builder()
            .id(PaymentFixtures.PAYMENT_ID).orderId(ORDER_ID).memberId(PaymentFixtures.MEMBER_ID)
            .pgOrderId(Long.toString(PaymentFixtures.PAYMENT_ID)).gateway(PaymentGateway.TOSS).totalAmount(1_000L).totalCurrency(KRW)
            .orderName(PaymentFixtures.ORDER_NAME).status(PaymentStatus.DONE).activeOrderId(ORDER_ID).version(0L)
            .build();

        assertThatThrownBy(() -> paymentMapper.toDomain(broken)).isInstanceOf(IllegalArgumentException.class);
    }

    /** delivery-service {@code MoneyColumnsTest} 와 같은 검사다 — 결제 쪽은 파일 수를 줄이려 매퍼 테스트에 합쳤다. */
    @Nested
    @DisplayName("MoneyColumns")
    class MoneyColumnsTest {

        @Test
        @DisplayName("required: 금액 · 통화를 Money 로 묶고, 통화가 없거나 음수면 데이터 오류다")
        void required() {
            assertThat(MoneyColumns.required(3_000L, KRW)).isEqualTo(Money.of(3_000, KRW));
            assertThatThrownBy(() -> MoneyColumns.required(3_000L, null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> MoneyColumns.required(-1L, KRW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("nullable: 둘 다 없으면 null, 둘 다 있으면 Money, 한쪽만 있으면 데이터 오류다")
        void nullable() {
            assertThat(MoneyColumns.nullable(null, null)).isNull();
            assertThat(MoneyColumns.nullable(50_000L, KRW)).isEqualTo(Money.of(50_000, KRW));
            assertThatThrownBy(() -> MoneyColumns.nullable(50_000L, null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> MoneyColumns.nullable(null, KRW)).isInstanceOf(IllegalStateException.class);
        }
    }
}
