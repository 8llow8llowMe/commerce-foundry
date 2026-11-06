package com.commercefoundry.domainlayer.order.application.service.processor;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.order.application.command.OrderReturnCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 소유·멱등 재생이 상태·수량 검사보다 앞선다는 업무 순서를 검증한다. */
class OrderReturnProcessorTest {
    private final OrderRepositoryPort orders = mock(OrderRepositoryPort.class);
    private final ClaimRepositoryPort claims = mock(ClaimRepositoryPort.class);
    private final OrderReturnProcessor processor = new OrderReturnProcessor(orders, claims, new SnowflakeIdGenerator(4, 4));

    private Order order(OrderStatus status) {
        return new Order(1, 7, status, domesticQuote(quoteLine(101, 1000, 1)), List.of(11L), address("KR"), 99,
            Instant.parse("2026-11-03T00:00:00Z"), null, null, Instant.parse("2026-11-03T00:00:00Z"), 1L);
    }

    private OrderReturnCommand command(ClaimItem.Reason reason) {
        return new OrderReturnCommand(List.of(new OrderReturnCommand.Item(11, 1, reason)));
    }

    @Test
    void unknownOwnerNeverReadsClaims() {
        when(orders.findByIdAndMemberIdForUpdate(1, 8)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> processor.request(8, 1, UUID.randomUUID(), command(ClaimItem.Reason.DEFECTIVE)))
            .isInstanceOfSatisfying(OrderException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.NOT_FOUND_ORDER));
        verifyNoInteractions(claims);
    }

    @Test
    void replayIgnoresLaterOrderStateAndDoesNotCountOrWriteAnything() {
        UUID key = UUID.randomUUID();
        Claim saved = new Claim(2, 1, key, Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), Instant.parse("2026-11-03T00:00:00Z"), 0L);
        when(orders.findByIdAndMemberIdForUpdate(1, 7)).thenReturn(Optional.of(order(OrderStatus.RETURNED)));
        when(claims.findByOrderIdAndRequestKey(1, key)).thenReturn(Optional.of(saved));
        var result = processor.request(7, 1, key, command(ClaimItem.Reason.DEFECTIVE));
        assertThat(result.created()).isFalse();
        assertThat(result.claim()).isEqualTo(saved);
        verify(claims, never()).sumRequestedReturnQuantities(anyLong());
        verify(claims, never()).insert(any());
        verify(orders, never()).saveStatusChange(any(), any());
    }

    @Test
    void changedReasonWithSameKeyFailsBeforeStatePolicy() {
        UUID key = UUID.randomUUID();
        Claim saved = new Claim(2, 1, key, Claim.Type.RETURN, Claim.Status.REQUESTED, null,
            List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.DEFECTIVE)), Instant.parse("2026-11-03T00:00:00Z"), 0L);
        when(orders.findByIdAndMemberIdForUpdate(1, 7)).thenReturn(Optional.of(order(OrderStatus.PURCHASE_CONFIRMED)));
        when(claims.findByOrderIdAndRequestKey(1, key)).thenReturn(Optional.of(saved));
        assertThatThrownBy(() -> processor.request(7, 1, key, command(ClaimItem.Reason.CHANGE_OF_MIND)))
            .isInstanceOfSatisfying(OrderException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.RETURN_KEY_REUSED));
        verify(claims, never()).sumRequestedReturnQuantities(anyLong());
    }

    /** 반품 API 에 결제 후 취소의 결정적 키를 넣어도(사유 · 수량이 같아 보여도) 취소 클레임을 반품으로 재생하지 않는다 (#16 PR-2). */
    @Test
    void cancellationClaimUnderSameKeyIsKeyReuse() {
        UUID key = Claim.cancellationRequestKey(1);
        Claim cancellation = new Claim(2, 1, key, Claim.Type.CANCELLATION, Claim.Status.REQUESTED, krw(4_000), 77L, 0,
            Instant.parse("2026-11-03T00:02:00Z"), null, List.of(new ClaimItem(3, 11, 1, ClaimItem.Reason.CHANGE_OF_MIND)),
            Instant.parse("2026-11-03T00:00:00Z"), 0L);
        when(orders.findByIdAndMemberIdForUpdate(1, 7)).thenReturn(Optional.of(order(OrderStatus.DELIVERED)));
        when(claims.findByOrderIdAndRequestKey(1, key)).thenReturn(Optional.of(cancellation));

        assertThatThrownBy(() -> processor.request(7, 1, key, command(ClaimItem.Reason.CHANGE_OF_MIND)))
            .isInstanceOfSatisfying(OrderException.class, e -> assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.RETURN_KEY_REUSED));
        verify(claims, never()).insert(any());
        verify(orders, never()).saveStatusChange(any(), any());
    }
}
