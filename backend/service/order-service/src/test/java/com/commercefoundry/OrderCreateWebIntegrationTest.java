package com.commercefoundry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commercefoundry.domainlayer.catalog.application.port.out.SkuQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.ShippingFeeQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRequestRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.query.StockReservationQueryResult;
import com.commercefoundry.domainlayer.order.application.exception.StockReservationException;
import com.commercefoundry.domainlayer.order.domain.model.OrderQuoteLine;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequest;
import com.commercefoundry.domainlayer.order.domain.model.OrderRequestItem;
import com.commercefoundry.domainlayer.order.support.OrderFixtures;
import com.commercefoundry.domainlayer.order.support.FakeShippingFeeQueryPort;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** 외부 포트만 가짜로 두고 HTTP → 실제 Facade → lease → 주문/이력 저장까지 통과한다. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderCreateWebIntegrationTest {
    private static final String BODY = """
        {"items":[{"skuId":"9001","quantity":1}],"shippingAddress":{
          "recipientName":"테스트", "recipientPhone":"010-0000-0001","countryCode":"KR",
          "postalCode":"63100","addressLine1":"테스트로","city":" 서울 ","stateProvince":" 테스트도 "}}
        """;
    @Autowired MockMvcTester mvc;
    @Autowired OrderRequestRepositoryPort requests;
    @Autowired OrderRepositoryPort orders;
    @Autowired SnowflakeIdGenerator ids;
    @MockitoBean SkuQueryPort skus;
    @MockitoBean ShippingFeeQueryPort shipping;
    @MockitoBean StockReservationPort stock;

    @BeforeEach
    void remotes() {
        when(skus.findSkus(any())).thenReturn(java.util.List.of(OrderFixtures.onSale(9001, 1000)));
        when(shipping.quote(any())).thenAnswer(call -> new FakeShippingFeeQueryPort().quote(call.getArgument(0)));
        when(stock.reserve(anyLong(), any())).thenAnswer(call ->
            new StockReservationQueryResult((long) call.getArgument(0) + 1, call.getArgument(0), Instant.now().plusSeconds(600)));
    }

    private MvcTestResult post(UUID key, String body, long member) {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(member, MemberRole.CUSTOMER).tokenValue();
        return mvc.post().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("Idempotency-Key", key.toString()).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    @Test
    void publicCreateReplayConflictAndMemberIsolation() {
        var key = UUID.randomUUID(); long member = TestTokens.MEMBER_ID;
        var first = post(key, BODY, member);
        assertThat(first).hasStatus(201);
        var request = requests.findByMemberIdAndIdempotencyKey(member, key).orElseThrow();
        assertThat(request.status()).isEqualTo(OrderRequest.Status.COMPLETED);
        var order = orders.findByIdAndMemberId(request.orderId(), member).orElseThrow();
        assertThat(order.shippingAddress().city()).isEqualTo("서울");
        assertThat(order.shippingAddress().stateProvince()).isEqualTo("테스트도");
        assertThat(order.shippingAddress().recipientPhone()).isEqualTo("01000000001");
        clearInvocations(skus, shipping, stock);
        var replay = post(key, BODY, member);
        assertThat(replay).hasStatusOk();
        assertThat(replay).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo(String.valueOf(order.id()));
        assertThat(replay).bodyJson().extractingPath("$.dataBody.shippingAddress.city").isEqualTo("서울");
        assertThat(post(key, BODY.replace("서울", "부산"), member)).hasStatus(409);
        verifyNoInteractions(skus, shipping, stock);
        assertThat(post(key, BODY, member + 1)).hasStatus(201);
        assertThat(requests.findByMemberIdAndIdempotencyKey(member + 1, key).orElseThrow().orderId()).isNotEqualTo(order.id());
    }

    @Test
    void unknownOutcomeEndsOnlyOwnedLeaseAndRetryReusesOrderId() {
        var key = UUID.randomUUID(); long member = TestTokens.MEMBER_ID;
        doThrow(new StockReservationException(StockReservationException.Failure.UNKNOWN)).when(stock).reserve(anyLong(), any());
        assertThat(post(key, BODY, member)).hasStatus(503);
        var failed = requests.findByMemberIdAndIdempotencyKey(member, key).orElseThrow();
        assertThat(failed.status()).isEqualTo(OrderRequest.Status.PROCESSING);
        assertThat(orders.findByIdAndMemberId(failed.orderId(), member)).isEmpty();
        doAnswer(call -> new StockReservationQueryResult((long) call.getArgument(0) + 1, call.getArgument(0), Instant.now().plusSeconds(600)))
            .when(stock).reserve(anyLong(), any());
        assertThat(post(key, BODY, member)).hasStatus(201);
        verify(stock, times(2)).reserve(eq(failed.orderId()), any());
    }

    @Test
    void expectedTotalMismatchDoesNotAcquireOrReserve() {
        var key = UUID.randomUUID();
        assertThat(post(key, BODY.strip().replaceFirst("}\\s*$", ",\"expectedTotalAmount\":{\"amount\":1,\"currency\":\"KRW\"}}"), TestTokens.MEMBER_ID))
            .hasStatus(409);
        assertThat(requests.findByMemberIdAndIdempotencyKey(TestTokens.MEMBER_ID, key)).isEmpty();
        verifyNoInteractions(stock);
    }

    @Test
    void ownerTakenOverDuringStockCallCannotSaveOrEndNewOwnersLease() {
        var key = UUID.randomUUID(); long member = TestTokens.MEMBER_ID;
        doAnswer(call -> {
            var original = requests.findByMemberIdAndIdempotencyKey(member, key).orElseThrow();
            Instant now = Instant.now();
            requests.endLease(original, now);
            var expired = requests.findByMemberIdAndIdempotencyKey(member, key).orElseThrow();
            requests.takeOver(expired, now.plusNanos(1000), java.time.Duration.ofSeconds(30)).orElseThrow();
            return new StockReservationQueryResult((long) call.getArgument(0) + 1, call.getArgument(0), now.plusSeconds(600));
        }).when(stock).reserve(anyLong(), any());
        assertThat(post(key, BODY, member)).hasStatus(409);
        var winner = requests.findByMemberIdAndIdempotencyKey(member, key).orElseThrow();
        assertThat(winner.version()).isEqualTo(2);
        assertThat(winner.isLeasedAt(Instant.now())).isTrue();
        assertThat(orders.findByIdAndMemberId(winner.orderId(), member)).isEmpty();
        clearInvocations(skus, shipping, stock);
        assertThat(post(key, BODY, member)).hasStatus(409);
        verifyNoInteractions(skus, shipping, stock);
    }

    @Test
    void inFlightQuantityOfAnotherAttemptIsRecheckedAfterAcquireAndEndedLeaseIsNotCounted() {
        long member = TestTokens.MEMBER_ID + 436_000 + ThreadLocalRandom.current().nextInt(1_000_000);
        long product = 9001 + 7000;
        var other = new AtomicReference<OrderRequest>();
        // 견적의 1차 판정이 끝난 뒤(배송비 견적 중) 같은 회원의 다른 시도가 같은 상품 수량을 쥔 기록을 커밋한다 — 기록을 잡은 뒤의 재검사가 잡아야 한다
        doAnswer(call -> {
            if (other.get() == null) {
                other.set(requests.insertIfAbsent(OrderRequest.start(ids.generateId(), member, UUID.randomUUID(), "d".repeat(64), ids.generateId(), Instant.now(),
                    Duration.ofSeconds(30)), List.of(new OrderRequestItem(product, OrderQuoteLine.MAX_QUANTITY))).orElseThrow());
            }
            return new FakeShippingFeeQueryPort().quote(call.getArgument(0));
        }).when(shipping).quote(any());

        var raced = UUID.randomUUID();
        var rejected = post(raced, BODY, member);
        assertThat(rejected).hasStatus(409);
        assertThat(rejected).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_005");
        verifyNoInteractions(stock);
        var ended = requests.findByMemberIdAndIdempotencyKey(member, raced).orElseThrow();
        assertThat(ended.status()).isEqualTo(OrderRequest.Status.PROCESSING);
        assertThat(ended.isLeasedAt(Instant.now())).as("넘친 시도는 lease 를 바로 끝낸다").isFalse();

        // 다른 시도가 lease 를 쥐고 있는 동안은 새 키도 견적 단계에서 거절된다 — 기록을 잡지 않는다
        var early = UUID.randomUUID();
        var earlyRejected = post(early, BODY, member);
        assertThat(earlyRejected).hasStatus(409);
        assertThat(earlyRejected).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_005");
        assertThat(requests.findByMemberIdAndIdempotencyKey(member, early)).isEmpty();

        // 그 시도가 실패해 lease 를 끝내면 세지 않는다 — 새 키로 곧바로 주문할 수 있다
        assertThat(requests.endLease(other.get(), Instant.now())).isTrue();
        assertThat(post(UUID.randomUUID(), BODY, member)).hasStatus(201);
        verify(stock, times(1)).reserve(anyLong(), any());
    }
}
