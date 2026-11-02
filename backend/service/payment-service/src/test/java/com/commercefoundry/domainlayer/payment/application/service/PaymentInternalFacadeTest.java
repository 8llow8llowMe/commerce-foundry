package com.commercefoundry.domainlayer.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.payment.adapter.in.internal.dto.response.PaymentInternalResponse;
import com.commercefoundry.domainlayer.payment.adapter.in.internal.presenter.PaymentInternalPresenter;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentErrorCode;
import com.commercefoundry.domainlayer.payment.application.exception.PaymentException;
import com.commercefoundry.domainlayer.payment.application.service.processor.PaymentProcessor;
import com.commercefoundry.domainlayer.payment.support.PaymentFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** 서비스 간 결제 조회 — 회원을 거르지 않는 조회 · 404 전달 · 읽기 트랜잭션. */
class PaymentInternalFacadeTest {

    private final PaymentProcessor processor = mock(PaymentProcessor.class);
    private final PaymentInternalFacade facade = new PaymentInternalFacade(processor, new PaymentInternalPresenter());

    @Test
    @DisplayName("회원과 상관없이 결제를 읽어 응답으로 바꾼다")
    void returnsPayment() {
        given(processor.getUnscoped(PaymentFixtures.PAYMENT_ID)).willReturn(PaymentFixtures.done());

        PaymentInternalResponse response = facade.getPayment(PaymentFixtures.PAYMENT_ID);

        assertThat(response.paymentId()).isEqualTo(Long.toString(PaymentFixtures.PAYMENT_ID));
        assertThat(response.memberId()).isEqualTo(Long.toString(PaymentFixtures.MEMBER_ID));
        assertThat(response.status().code()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("없는 결제는 404 PAYMENT_001 을 그대로 올린다")
    void notFound() {
        given(processor.getUnscoped(PaymentFixtures.PAYMENT_ID)).willThrow(new PaymentException(PaymentErrorCode.NOT_FOUND_PAYMENT));

        assertThatThrownBy(() -> facade.getPayment(PaymentFixtures.PAYMENT_ID))
            .isInstanceOfSatisfying(PaymentException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.NOT_FOUND_PAYMENT));
    }

    @Test
    @DisplayName("외부 I/O 가 없는 읽기라 클래스에 읽기 전용 트랜잭션을 건다")
    void readOnlyTransaction() {
        Transactional transactional = PaymentInternalFacade.class.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
