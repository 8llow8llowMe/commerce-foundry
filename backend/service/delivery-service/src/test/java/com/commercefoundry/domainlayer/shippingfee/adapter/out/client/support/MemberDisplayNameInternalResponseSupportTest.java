package com.commercefoundry.domainlayer.shippingfee.adapter.out.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.dto.MemberDisplayNameInternalItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.dto.MemberDisplayNameInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import feign.FeignException;
import feign.Request;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberDisplayNameInternalResponseSupportTest {

    private static final Request REQUEST = Request.create(
        Request.HttpMethod.GET, "http://auth-service/internal/v1/member-display-names", Map.of(), null, StandardCharsets.UTF_8, null);

    private CircuitBreakerRegistry registry;
    private MemberDisplayNameInternalResponseSupport support;

    @BeforeEach
    void setUp() {
        registry = CircuitBreakerRegistry.ofDefaults();
        support = new MemberDisplayNameInternalResponseSupport(registry);
    }

    @Test
    @DisplayName("마스킹된 이름을 아이디로 읽고, 없는 이름은 null 이다")
    void readsMaskedNames() {
        Response<MemberDisplayNameInternalResponse> response = Response.success(new MemberDisplayNameInternalResponse(List.of(
            new MemberDisplayNameInternalItem("11", "홍*동"),
            new MemberDisplayNameInternalItem("22", null)
        )));

        assertThat(support.toMaskedNames(response)).containsEntry(11L, "홍*동").containsKey(22L);
        assertThat(support.toMaskedNames(response).get(22L)).isNull();
    }

    @Test
    @DisplayName("봉투가 실패이거나 아이디가 아니면 503 DELIVERY_013 이다 — 이름을 null 로 채우지 않는다")
    void rejectsBrokenContract() {
        assertUnavailable(() -> support.toMaskedNames(Response.fail("AUTH_137", "ids 는 필수입니다.")));
        assertUnavailable(() -> support.toMaskedNames(Response.success(new MemberDisplayNameInternalResponse(List.of(
            new MemberDisplayNameInternalItem("nope", "홍*동")
        )))));
    }

    @Test
    @DisplayName("서킷이 열려 있거나 4xx 면 503 DELIVERY_013 이다")
    void remoteFailure() {
        registry.circuitBreaker(MemberDisplayNameInternalResponseSupport.CIRCUIT_BREAKER).transitionToOpenState();
        assertUnavailable(() -> support.call(() -> "ok"));

        MemberDisplayNameInternalResponseSupport closed = new MemberDisplayNameInternalResponseSupport(CircuitBreakerRegistry.ofDefaults());
        FeignException rejected = FeignException.errorStatus(
            "MemberDisplayNameClient#getDisplayNames(List)",
            feign.Response.builder().status(400).reason("bad").request(REQUEST).headers(Map.of()).body(new byte[0]).build());
        assertUnavailable(() -> closed.call(() -> {
            throw rejected;
        }));
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DeliveryException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(DeliveryErrorCode.AUTH_SERVICE_UNAVAILABLE));
    }
}
