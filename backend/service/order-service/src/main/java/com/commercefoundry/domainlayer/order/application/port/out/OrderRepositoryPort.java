package com.commercefoundry.domainlayer.order.application.port.out;

import com.commercefoundry.domainlayer.order.application.port.out.query.OrderAdminSummaryQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderExpiryCandidateQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderMemberSummaryQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.OrderStatusHistoryQueryResult;
import com.commercefoundry.domainlayer.order.application.port.out.query.PendingProductQuantityQueryResult;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;

/**
 * 주문 — 주문 행 · 배송지 · 항목 · 맞춤 제작 입력 · 상태 이력. 단건 조회는 모두 채워 돌려준다 (주문 1번 + 배송지 1번 + 항목 1번 + 입력 1번).
 * 만료 대상 조회는 아이디 · 기한만 투영한다.
 */
public interface OrderRepositoryPort {

    /**
     * 새 주문(배송지 · 항목 · 입력 포함)과 첫 상태 이력을 넣고(persist — 같은 아이디는 PK 위반) 바로 flush 한다. 입력 행 아이디는 저장할 때 발급한다.
     *
     * @return 저장된 주문 — 생성 시각 · 버전(0)이 채워진다. 생성 시각은 감사가 채운 메모리 값이라 DB 에 저장된 값({@code timestamp(6)}, 드라이버가 반올림)과
     *     마이크로초 아래 자리가 다를 수 있다
     * @throws com.commercefoundry.domainlayer.order.application.exception.OrderSaveException 사유는 {@code Reason}
     */
    Order insert(Order order, OrderStatusHistory history);

    /** 회원의 주문 하나. 없거나 다른 회원의 주문이면 빈 값이다. */
    Optional<Order> findByIdAndMemberId(long orderId, long memberId);

    /** 주문 하나(회원 무관) — 시스템 전이(만료 스윕)와 어드민(#17)이 쓴다. 고객 조회에 쓰지 않는다(소유 확인은 {@link #findByIdAndMemberId}). */
    Optional<Order> findById(long orderId);

    /** 회원의 결제 대기 주문에 담긴 상품별 수량 합 (#426). 담긴 적 없는 상품은 결과에 없다. 호출한 쪽 트랜잭션에서 읽는다. */
    List<PendingProductQuantityQueryResult> sumPendingQuantitiesByProduct(long memberId, Collection<Long> productIds);

    /**
     * 어드민 주문 목록 (#17) — 주문 시각 {@code [from, to)} 안의 주문을 아이디 내림차순(최신순) 한 페이지. 상태가 null 이면 전체다.
     * 첫 항목 상품명 · 항목 수 · 받는 사람은 그 페이지 주문만 묶어 읽는다(N+1 없음).
     */
    Page<OrderAdminSummaryQueryResult> findAdminSummaryPage(OrderStatus status, Instant from, Instant to, int page, int size);

    /** 주문의 상태 이력 — 오래된 것부터. */
    List<OrderStatusHistoryQueryResult> findHistories(long orderId);

    /**
     * 상태를 바꾼 주문 헤더와 그 이력을 저장하고 flush 한다 (#17 어드민). 배송지 · 항목 · 입력 스냅숏은 다시 저장하지 않는다. 호출 Processor 의 트랜잭션을 요구한다.
     *
     * @param changed 도메인 {@code changeStatus} 로 바뀐 주문 — 버전은 읽을 때 값이다
     * @throws IllegalArgumentException 주문 · 이력이 서로 맞지 않을 때
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 다른 곳(고객 · 결제 · 다른 운영자)이 먼저 커밋했을 때
     */
    void saveStatusChange(Order changed, OrderStatusHistory history);

    /**
     * 결제 기한이 지난({@code paymentDueAt <= now}) 결제 대기 주문을 {@code (paymentDueAt, orderId)} 오름차순으로 {@code limit} 건까지 읽는다.
     * 첫 배치다 — 다음 배치는 {@link #findDueForExpiryAfter} 로 읽는다.
     */
    List<OrderExpiryCandidateQueryResult> findDueForExpiry(Instant now, int limit);

    /** {@link #findDueForExpiry} 의 다음 배치 — 앞 배치 마지막 건 {@code (afterDueAt, afterOrderId)} 다음부터 읽는다(keyset). */
    List<OrderExpiryCandidateQueryResult> findDueForExpiryAfter(Instant now, Instant afterDueAt, long afterOrderId, int limit);

    /**
     * 만료 전이(PENDING_PAYMENT → EXPIRED)한 주문 헤더와 그 이력을 저장하고 flush 한다 — 만료 전용이다. 배송지 · 항목 · 입력 스냅숏은 다시 저장하지 않는다.
     * 호출 Processor 의 트랜잭션을 필수로 요구한다 — 뒤쪽 이력 · outbox 실패도 상태와 함께 롤백한다.
     *
     * @param expired 도메인 {@code expire(now)} 로 바뀐 주문 — 버전은 읽을 때 값이다
     * @throws IllegalArgumentException 주문 · 이력이 만료 전이가 아니거나 서로 맞지 않을 때
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 결제 완료 · 다른 인스턴스의 만료가 먼저 커밋했을 때
     */
    void saveExpiry(Order expired, OrderStatusHistory history);

    /**
     * 회원의 주문 내역 한 조각 (#16) — 최신순(주문 아이디 내림차순, {@code idx_orders_member_id_id}). 전체 건수를 세지 않는다.
     * 첫 항목 상품명 · 항목 수는 그 조각의 주문만 묶어 읽는다(N+1 없음).
     */
    Slice<OrderMemberSummaryQueryResult> findMemberSummarySlice(long memberId, int page, int size);

    /**
     * 고객의 결제 전 취소(PENDING_PAYMENT → CANCELED, #16)한 주문 헤더와 그 이력을 저장하고 flush 한다. 호출 Processor 의 트랜잭션을 필수로 요구한다 —
     * 뒤의 {@code order-canceled} outbox 실패도 상태와 함께 롤백한다.
     *
     * @param canceled 도메인 {@code cancelBeforePayment()} 로 바뀐 주문 — 버전은 읽을 때 값이다
     * @throws IllegalArgumentException 주문 · 이력이 결제 전 고객 취소가 아니거나 서로 맞지 않을 때
     * @throws org.springframework.dao.OptimisticLockingFailureException 읽은 뒤 결제 완료 · 만료 · 다른 취소가 먼저 커밋했을 때
     */
    void saveCustomerCancel(Order canceled, OrderStatusHistory history);
}
