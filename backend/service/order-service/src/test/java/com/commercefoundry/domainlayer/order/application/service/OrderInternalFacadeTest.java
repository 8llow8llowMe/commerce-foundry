package com.commercefoundry.domainlayer.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderFulfillmentInternalResponse;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderFulfillmentInternalPresenter;
import com.commercefoundry.domainlayer.order.adapter.in.internal.presenter.OrderPaymentSummaryInternalPresenter;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.info.OrderFulfillmentInfo;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderFulfillmentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentSummaryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderProcessor;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/** 출고 · 배송 완료 반영(#18)의 Facade — 버전 충돌 번역과 응답 조립. 전이 · 저장 · 멱등은 영속성 계약({@code OrderAdminPersistenceContract})이 본다. */
class OrderInternalFacadeTest {

    private static final long ORDER_ID = 4611686018427387904L;
    private static final long MEMBER_ID = 4611686018427387905L;
    private static final long DELIVERY_ID = 4611686018427387906L;
    private static final Instant AT = Instant.parse("2026-11-02T09:30:00Z");

    private final OrderFulfillmentProcessor processor = mock(OrderFulfillmentProcessor.class);
    private final OrderInternalFacade facade = new OrderInternalFacade(mock(OrderProcessor.class), mock(OrderPaymentSummaryProcessor.class),
        mock(OrderPaymentSummaryInternalPresenter.class), processor, new OrderFulfillmentInternalPresenter());

    @Test
    @DisplayName("출고 반영 결과를 아이디 문자열 · 상태 제어 코드 · 배송 국가로 낸다 — 멱등 재요청(바꾸지 않음)도 같은 모양이다")
    void shipResponse() {
        given(processor.ship(ORDER_ID, AT)).willReturn(new OrderFulfillmentInfo(ORDER_ID, MEMBER_ID, OrderStatus.SHIPPING, "US", false));

        OrderFulfillmentInternalResponse response = facade.ship(ORDER_ID, DELIVERY_ID, AT);

        assertThat(response.orderId()).isEqualTo("4611686018427387904");
        assertThat(response.memberId()).isEqualTo("4611686018427387905");
        assertThat(response.status()).isEqualTo("SHIPPING");
        assertThat(response.countryCode()).isEqualTo("US");
    }

    @Test
    @DisplayName("배송 완료 반영 결과도 같은 모양이다")
    void completeDeliveryResponse() {
        given(processor.completeDelivery(ORDER_ID, AT)).willReturn(new OrderFulfillmentInfo(ORDER_ID, MEMBER_ID, OrderStatus.DELIVERED, "KR", true));

        assertThat(facade.completeDelivery(ORDER_ID, DELIVERY_ID, AT).status()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("읽은 뒤 다른 곳이 먼저 바꿔 버전 충돌이면 ORDER_025 다 — 출고 · 배송 완료 둘 다")
    void versionConflictIsConcurrentlyChanged() {
        given(processor.ship(ORDER_ID, AT)).willThrow(new OptimisticLockingFailureException("conflict"));
        given(processor.completeDelivery(ORDER_ID, AT)).willThrow(new OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> facade.ship(ORDER_ID, DELIVERY_ID, AT))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED));
        assertThatThrownBy(() -> facade.completeDelivery(ORDER_ID, DELIVERY_ID, AT))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_CONCURRENTLY_CHANGED));
    }

    @Test
    @DisplayName("업무 오류(없음 · 표 밖)는 그대로 흘린다")
    void businessErrorsPassThrough() {
        given(processor.ship(ORDER_ID, AT)).willThrow(new OrderException(OrderErrorCode.NOT_FOUND_ORDER));

        assertThatThrownBy(() -> facade.ship(ORDER_ID, DELIVERY_ID, AT))
            .isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.NOT_FOUND_ORDER));
    }
}
