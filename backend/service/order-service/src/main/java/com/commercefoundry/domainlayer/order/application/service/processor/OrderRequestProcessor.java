package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 주문 요청 기록 — 주문 생성의 멱등 ({@code api-design-guide.md} §6, {@code order-flow-guide.md} §2-4). Redis 가 아니라 DB 유니크 키
 * ({@code uk_order_request_member_id_idempotency_key})가 최종 보장이다.
 *
 * <p><b>메서드에 트랜잭션을 걸지 않는다.</b> 기록을 넣다 유니크 키에 걸리면 PostgreSQL 은 그 트랜잭션을 중단 상태로 만들어 이어지는 조회까지 실패한다.
 * 넣기 · 읽기 · 이어받기를 리포지토리 호출 하나씩(각자 짧은 트랜잭션)으로 나누고, 충돌하면 <b>새 트랜잭션</b>(다음 리포지토리 호출)에서 다시 읽어
 * {@link #check} 의 규칙으로 답한다. 그 사이의 경합은 유니크 키와 버전(@Version)이 하나만 통과시킨다.
 *
 * <p>로그에는 회원 아이디 · 주문 아이디만 남긴다 — 키 · 요청 지문은 남기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRequestProcessor {

    private final OrderRequestRepositoryPort orderRequestRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final OrderProperties orderProperties;
    private final Clock clock;

    /**
     * 기록을 본다 (생성 순서 1). 부작용이 없다.
     *
     * <ul>
     *   <li>없다 — {@code NONE}</li>
     *   <li>본문 지문이 다르다 — 상태와 무관하게 409 {@code IDEMPOTENCY_KEY_REUSED}(이어받지 않는다)</li>
     *   <li>완료 — {@code REPLAY}</li>
     *   <li>처리 중이고 lease 안 — 409 {@code IDEMPOTENCY_IN_PROGRESS}</li>
     *   <li>처리 중이고 lease 가 지났다 — {@code TAKE_OVER}(계산 뒤 {@link #acquire} 에서 이어받는다)</li>
     * </ul>
     */
    public OrderRequestClaim check(long memberId, UUID idempotencyKey, String requestHash) {
        return orderRequestRepositoryPort.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey)
            .map(existing -> decide(existing, requestHash))
            .orElseGet(OrderRequestClaim::none);
    }

    /**
     * 기록을 잡는다 (생성 순서 3) — 새 기록을 넣거나({@code NONE}), lease 가 지난 기록을 같은 주문 아이디로 이어받는다({@code TAKE_OVER}).
     * 유니크 · 버전 충돌이면 다시 읽어 {@link #check} 의 규칙으로 답한다 — 그 사이 완료됐으면 {@code REPLAY}, 아니면 409.
     *
     * @param checked {@link #check} 의 결과 ({@code NONE} 또는 {@code TAKE_OVER})
     * @return {@code ACQUIRED} 또는 {@code REPLAY}
     * @throws OrderException {@code IDEMPOTENCY_IN_PROGRESS} · {@code IDEMPOTENCY_KEY_REUSED}(409)
     */
    public OrderRequestClaim acquire(long memberId, UUID idempotencyKey, String requestHash, OrderRequestClaim checked) {
        Optional<OrderRequest> acquired = switch (checked.outcome()) {
            case NONE -> orderRequestRepositoryPort.insertIfAbsent(OrderRequest.start(
                snowflakeIdGenerator.generateId(), memberId, idempotencyKey, requestHash, snowflakeIdGenerator.generateId(), clock.instant(), lease()));
            case TAKE_OVER -> orderRequestRepositoryPort.saveIfUnchanged(checked.request().takeOver(clock.instant(), lease()));
            case ACQUIRED, REPLAY -> throw new IllegalStateException("잡을 수 없는 확인 결과입니다: " + checked.outcome());
        };
        if (acquired.isPresent()) {
            if (checked.outcome() == OrderRequestClaim.Outcome.TAKE_OVER) {
                log.info("order request taken over. memberId={}, orderId={}", memberId, acquired.get().orderId());
            }
            return new OrderRequestClaim(OrderRequestClaim.Outcome.ACQUIRED, acquired.get());
        }
        // 다른 시도가 먼저 넣었거나 이어받았다 — 새 트랜잭션에서 다시 읽어 같은 규칙으로 답한다. 다시 이어받으려 하지 않는다(경합을 키우지 않게)
        OrderRequestClaim reread = check(memberId, idempotencyKey, requestHash);
        if (reread.isReplay()) {
            return reread;
        }
        throw new OrderException(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    /**
     * 처리한 실패 — lease 를 바로 끝내 같은 키의 재시도가 곧바로 이어받게 한다(기록과 주문 아이디는 남는다). 던지지 않는다: 원래 실패를 가리지 않게 로그만 남긴다.
     * 다른 시도가 이미 이어받았으면(버전이 다르다) 그쪽 것이라 건드리지 않는다. 끝내지 못한 lease 는 시간이 지나면 끝난다.
     */
    public void endLeaseQuietly(OrderRequest request) {
        try {
            if (orderRequestRepositoryPort.saveIfUnchanged(request.endLease(clock.instant())).isEmpty()) {
                log.info("order request lease not ended — taken over. orderId={}", request.orderId());
            }
        } catch (RuntimeException exception) {
            log.warn("order request lease not ended. orderId={}, type={}", request.orderId(), exception.getClass().getSimpleName());
        }
    }

    private OrderRequestClaim decide(OrderRequest existing, String requestHash) {
        if (!existing.matches(requestHash)) {
            throw new OrderException(OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (existing.status() == OrderRequest.Status.COMPLETED) {
            return new OrderRequestClaim(OrderRequestClaim.Outcome.REPLAY, existing);
        }
        if (existing.isLeasedAt(clock.instant())) {
            throw new OrderException(OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
        return new OrderRequestClaim(OrderRequestClaim.Outcome.TAKE_OVER, existing);
    }

    private Duration lease() {
        return orderProperties.creation().processingLease();
    }
}
