package com.commercefoundry.domainlayer.order.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.order.application.exception.OrderErrorCode;
import com.commercefoundry.domainlayer.order.application.exception.OrderException;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim;
import com.commercefoundry.domainlayer.order.application.model.OrderRequestClaim.Outcome;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.support.InMemoryOrderRequestRepository;
import com.commercefoundry.global.properties.OrderProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 주문 요청 기록 규칙 — 다른 본문 409 · 완료 재생 · lease 안 409 · lease 지나면 같은 주문 아이디로 이어받기 · 실패는 lease 를 바로 끝낸다. */
class OrderRequestProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final long MEMBER = 7L;
    private static final UUID KEY = UUID.fromString("3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11");
    private static final String HASH = "a".repeat(64);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private InMemoryOrderRequestRepository repository;
    private OrderRequestProcessor processor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOrderRequestRepository();
        processor = new OrderRequestProcessor(repository, new SnowflakeIdGenerator(1, 3), new OrderProperties(new OrderProperties.Creation(LEASE)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenRow(OrderRequest.Status status, Instant leaseExpiresAt) {
        repository.givenSaved(new OrderRequest(1L, MEMBER, KEY, HASH, 777L, status, leaseExpiresAt, 4L));
    }

    @Test
    @DisplayName("기록이 없으면 NONE, 잡으면 주문 아이디를 미리 발급해 처리 중 · lease 60s 로 넣는다")
    void newRequest() {
        OrderRequestClaim checked = processor.check(MEMBER, KEY, HASH);
        assertThat(checked.outcome()).isEqualTo(Outcome.NONE);

        OrderRequestClaim claim = processor.acquire(MEMBER, KEY, HASH, checked);

        assertThat(claim.outcome()).isEqualTo(Outcome.ACQUIRED);
        assertThat(claim.request().status()).isEqualTo(OrderRequest.Status.PROCESSING);
        assertThat(claim.request().leaseExpiresAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(claim.request().version()).isZero();
        assertThat(claim.orderId()).isPositive();
    }

    @Test
    @DisplayName("본문 지문이 다르면 상태와 무관하게 409 ORDER_019 — 완료 · 처리 중 · lease 지남 모두, 이어받지 않는다")
    void reusedKey() {
        for (Instant lease : new Instant[] {NOW.plusSeconds(30), NOW.minusSeconds(1)}) {
            givenRow(OrderRequest.Status.PROCESSING, lease);
            assertCode(() -> processor.check(MEMBER, KEY, "b".repeat(64)), OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        givenRow(OrderRequest.Status.COMPLETED, NOW.minusSeconds(1));
        assertCode(() -> processor.check(MEMBER, KEY, "b".repeat(64)), OrderErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    @DisplayName("완료된 같은 본문은 REPLAY — 같은 주문 아이디")
    void completedReplays() {
        givenRow(OrderRequest.Status.COMPLETED, NOW.minusSeconds(120));

        OrderRequestClaim claim = processor.check(MEMBER, KEY, HASH);

        assertThat(claim.isReplay()).isTrue();
        assertThat(claim.orderId()).isEqualTo(777L);
    }

    @Test
    @DisplayName("처리 중이고 lease 안이면 409 ORDER_018 — lease 가 끝나는 바로 그 시각부터는 이어받는다")
    void leaseBoundary() {
        givenRow(OrderRequest.Status.PROCESSING, NOW.plusMillis(1));
        assertCode(() -> processor.check(MEMBER, KEY, HASH), OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);

        givenRow(OrderRequest.Status.PROCESSING, NOW);
        assertThat(processor.check(MEMBER, KEY, HASH).outcome()).isEqualTo(Outcome.TAKE_OVER);
    }

    @Test
    @DisplayName("lease 가 지난 기록은 같은 주문 아이디로 이어받는다 — lease 를 새로 잡고 버전이 오른다")
    void takeOver() {
        givenRow(OrderRequest.Status.PROCESSING, NOW.minusSeconds(1));

        OrderRequestClaim claim = processor.acquire(MEMBER, KEY, HASH, processor.check(MEMBER, KEY, HASH));

        assertThat(claim.outcome()).isEqualTo(Outcome.ACQUIRED);
        assertThat(claim.orderId()).isEqualTo(777L);
        assertThat(claim.request().leaseExpiresAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(claim.request().version()).isEqualTo(5L);
    }

    @Test
    @DisplayName("넣기에서 지면 다시 읽어 같은 규칙으로 답한다 — 그 사이 완료면 REPLAY, 처리 중이면 409")
    void insertRace() {
        OrderRequestClaim checked = processor.check(MEMBER, KEY, HASH);
        givenRow(OrderRequest.Status.COMPLETED, NOW);
        assertThat(processor.acquire(MEMBER, KEY, HASH, checked).isReplay()).isTrue();

        givenRow(OrderRequest.Status.PROCESSING, NOW.plusSeconds(30));
        assertCode(() -> processor.acquire(MEMBER, KEY, HASH, OrderRequestClaim.none()), OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    @Test
    @DisplayName("이어받기에서 지면(버전이 다르다) 409 ORDER_018 — 다른 시도가 먼저 이어받았다")
    void takeOverRace() {
        givenRow(OrderRequest.Status.PROCESSING, NOW.minusSeconds(1));
        OrderRequestClaim checked = processor.check(MEMBER, KEY, HASH);
        repository.givenSaved(new OrderRequest(1L, MEMBER, KEY, HASH, 777L, OrderRequest.Status.PROCESSING, NOW.plusSeconds(60), 5L));

        assertCode(() -> processor.acquire(MEMBER, KEY, HASH, checked), OrderErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    @Test
    @DisplayName("처리한 실패는 lease 를 바로 끝낸다 — 주문 아이디는 남고 같은 키의 재시도가 곧바로 이어받는다")
    void endLease() {
        OrderRequestClaim claim = processor.acquire(MEMBER, KEY, HASH, OrderRequestClaim.none());

        processor.endLeaseQuietly(claim.request());

        OrderRequestClaim retry = processor.check(MEMBER, KEY, HASH);
        assertThat(retry.outcome()).isEqualTo(Outcome.TAKE_OVER);
        assertThat(retry.orderId()).isEqualTo(claim.orderId());
    }

    @Test
    @DisplayName("이미 다른 시도가 이어받은 기록의 lease 끝내기는 무시된다 — 던지지 않고 그쪽 lease 를 건드리지 않는다")
    void staleEndLeaseIsIgnored() {
        OrderRequestClaim first = processor.acquire(MEMBER, KEY, HASH, OrderRequestClaim.none());
        repository.givenSaved(new OrderRequest(first.request().id(), MEMBER, KEY, HASH, first.orderId(), OrderRequest.Status.PROCESSING, NOW.plusSeconds(60), 3L));

        assertThatCode(() -> processor.endLeaseQuietly(first.request())).doesNotThrowAnyException();
        assertThat(repository.all()).singleElement().extracting(OrderRequest::leaseExpiresAt).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("기록의 문자열 표현에 요청 지문이 없다 — 로그로 새지 않는다")
    void toStringHidesHash() {
        assertThat(OrderRequest.start(1L, MEMBER, KEY, HASH, 2L, NOW, LEASE).toString()).doesNotContain(HASH).doesNotContain(KEY.toString());
    }

    private static void assertCode(ThrowingCallable call, OrderErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(OrderException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
