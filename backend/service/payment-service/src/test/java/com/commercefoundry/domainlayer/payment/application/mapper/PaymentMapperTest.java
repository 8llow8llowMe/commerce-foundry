package com.commercefoundry.domainlayer.payment.application.mapper;

import static com.commercefoundry.domainlayer.payment.support.PaymentFixtures.ORDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.entity.PaymentEntity;
import com.commercefoundry.domainlayer.payment.domain.model.Payment;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentGateway;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentStatus;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentMapperTest {

    private final PaymentMapper paymentMapper = new PaymentMapperImpl();

    static Stream<Arguments> payments() {
        return Stream.of(
            Arguments.of("READY", (Supplier<Payment>) PaymentFixtures::ready),
            Arguments.of("IN_PROGRESS", (Supplier<Payment>) PaymentFixtures::inProgress),
            Arguments.of("DONE", (Supplier<Payment>) PaymentFixtures::done),
            Arguments.of("DONE 주문 반영 실패", (Supplier<Payment>) () -> PaymentFixtures.done().markOrderSyncFailed("ORDER_030")),
            Arguments.of("FAILED", (Supplier<Payment>) PaymentFixtures::failed),
            Arguments.of("UNKNOWN", (Supplier<Payment>) PaymentFixtures::unknown),
            Arguments.of("ABORTED", (Supplier<Payment>) PaymentFixtures::aborted)
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
    @DisplayName("금액은 amount + currency 컬럼으로, 활성 슬롯은 상태에서 유도해 쓴다")
    void writesColumns() {
        PaymentEntity active = paymentMapper.toEntity(PaymentFixtures.done());
        PaymentEntity released = paymentMapper.toEntity(PaymentFixtures.failed());

        assertThat(active.getAmount()).isEqualTo(129_000L);
        assertThat(active.getCurrency()).isEqualTo(CurrencyCode.KRW);
        assertThat(active.getActiveOrderId()).isEqualTo(ORDER_ID);
        assertThat(active.getVersion()).as("새 결제 시도는 버전이 없다 — persist 로 간다").isNull();
        assertThat(released.getActiveOrderId()).isNull();
    }

    @Test
    @DisplayName("통화가 빠진 행은 데이터 오류로 읽지 않는다")
    void missingCurrencyIsDataError() {
        PaymentEntity broken = PaymentEntity.builder()
            .id(PaymentFixtures.PAYMENT_ID).orderId(ORDER_ID).memberId(PaymentFixtures.MEMBER_ID)
            .pgOrderId(Long.toString(PaymentFixtures.PAYMENT_ID)).gateway(PaymentGateway.TOSS).amount(1_000L)
            .orderName(PaymentFixtures.ORDER_NAME).status(PaymentStatus.READY).activeOrderId(ORDER_ID).version(0L)
            .build();

        assertThatThrownBy(() -> paymentMapper.toDomain(broken)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("상태와 맞지 않는 칸이 있는 행은 도메인 불변식으로 읽지 않는다")
    void inconsistentRowIsRejected() {
        PaymentEntity broken = PaymentEntity.builder()
            .id(PaymentFixtures.PAYMENT_ID).orderId(ORDER_ID).memberId(PaymentFixtures.MEMBER_ID)
            .pgOrderId(Long.toString(PaymentFixtures.PAYMENT_ID)).gateway(PaymentGateway.TOSS).amount(1_000L).currency(CurrencyCode.KRW)
            .orderName(PaymentFixtures.ORDER_NAME).status(PaymentStatus.DONE).activeOrderId(ORDER_ID).version(0L)
            .build();

        assertThatThrownBy(() -> paymentMapper.toDomain(broken)).isInstanceOf(IllegalArgumentException.class);
    }
}
