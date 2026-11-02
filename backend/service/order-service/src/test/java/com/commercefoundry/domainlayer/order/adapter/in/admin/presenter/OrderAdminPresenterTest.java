package com.commercefoundry.domainlayer.order.adapter.in.admin.presenter;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.order.adapter.in.admin.dto.response.OrderAdminResponse;
import com.commercefoundry.domainlayer.order.application.model.OrderAdminDetail;
import com.commercefoundry.domainlayer.order.application.info.OrderStatusHistoryInfo;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.order.domain.model.StatusChangedBy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 어드민 주문 응답 (#17) — 배송지 마스킹 · 주문 이름 · 이력 표시. 테스트 값은 모두 가짜다. */
class OrderAdminPresenterTest {

    private final OrderAdminPresenter presenter = new OrderAdminPresenter();

    @Test
    @DisplayName("상세 — 받는 분 · 연락처 · 상세 주소 · 요청 사항은 가리고, 이력은 업무 언어로 바꾼 쪽을 싣는다")
    void detailMasksAddressAndDescribesHistory() {
        ShippingAddress address = new ShippingAddress("테스트수령인", "01000000001", "KR", "63100", "테스트시 테스트로 1", "101동 1001호", null, null, "공동현관 0000#");
        Order order = new Order(1, 2, OrderStatus.PAID, domesticQuote(quoteLine(101, 1000, 1)), List.of(3L), address, 4,
            Instant.parse("2026-10-18T09:30:00Z"), null, null, Instant.parse("2026-10-18T09:00:00Z"), 1L);
        OrderAdminDetail detail = new OrderAdminDetail(order, List.of(
            new OrderStatusHistoryInfo(null, OrderStatus.PENDING_PAYMENT, StatusChangedBy.CUSTOMER, 2L, null, Instant.parse("2026-10-18T09:00:00Z")),
            new OrderStatusHistoryInfo(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, StatusChangedBy.SERVICE, null, null, Instant.parse("2026-10-18T09:05:00Z"))
        ), Set.of(OrderStatus.PREPARING));

        OrderAdminResponse response = presenter.toResponse(detail);

        assertThat(response.shippingAddress().recipientName()).isEqualTo("테*인");
        assertThat(response.shippingAddress().recipientPhone()).isEqualTo("010****0001");
        assertThat(response.shippingAddress().addressLine1()).isEqualTo("테스트시 테스트로 1");
        assertThat(response.shippingAddress().addressLine2()).isEqualTo("****");
        assertThat(response.shippingAddress().deliveryMemo()).isEqualTo("****");
        assertThat(response.toString()).doesNotContain("테스트수령인", "0000#");
        assertThat(response.nextStatuses()).extracting(metadata -> metadata.code()).containsExactly("PREPARING");
        assertThat(response.histories()).extracting(item -> item.changedBy().name()).containsExactly("고객", "연동 처리");
        assertThat(response.histories().getFirst().fromStatus()).isNull();
        assertThat(response.histories().getLast().changedById()).isNull();
    }

    @Test
    @DisplayName("마스킹 · 주문 이름 규칙")
    void rules() {
        assertThat(OrderAdminMasking.maskName("홍")).isEqualTo("*");
        assertThat(OrderAdminMasking.maskName("홍길")).isEqualTo("홍*");
        assertThat(OrderAdminMasking.maskName(" John Smith ")).isEqualTo("J*h");
        assertThat(OrderAdminMasking.maskPhone("123456")).isEqualTo("****");
        assertThat(OrderAdminMasking.maskPhone("+821000000001")).isEqualTo("+82****0001");
        assertThat(OrderAdminMasking.maskIfPresent(" ")).isNull();
        assertThat(OrderAdminPresenter.orderName("저고리", 1)).isEqualTo("저고리");
        assertThat(OrderAdminPresenter.orderName("저고리", 3)).isEqualTo("저고리 외 2건");
        assertThat(OrderAdminPresenter.orderName(null, 0)).isNull();
    }
}
