package com.commercefoundry.domainlayer.picking.adapter.out.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.OrderPickingClient;
import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.dto.OrderPickingSnapshotInternalResponse;
import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.dto.OrderPickingSnapshotInternalResponse.Line;
import com.commercefoundry.domainlayer.picking.adapter.out.client.support.OrderPickingInternalResponseSupport;
import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 피킹 스냅숏 어댑터 — 벌크 호출 한 번 · 응답 변환 · 실패를 업무 503 으로 바꾸는지(원격 메시지를 싣지 않는지) 본다. */
class OrderPickingSnapshotClientAdapterTest {

    private static final Request REQUEST = Request.create(Request.HttpMethod.GET, "http://order-service/internal/v1/orders/picking-snapshots", Map.of(), null,
        StandardCharsets.UTF_8, null);

    private final OrderPickingClient client = mock(OrderPickingClient.class);
    private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private final OrderPickingSnapshotClientAdapter adapter = new OrderPickingSnapshotClientAdapter(client, new OrderPickingInternalResponseSupport(registry));

    private static OrderPickingSnapshotInternalResponse snapshot(String id) {
        return new OrderPickingSnapshotInternalResponse(id, "PREPARING", "KR", List.of(new Line("10", 2L, false), new Line("10", 3L, false)));
    }

    private static FeignException status(int status) {
        feign.Response response = feign.Response.builder().status(status).reason("x").request(REQUEST).headers(Map.of())
            .body("{\"dataHeader\":{\"success\":false,\"resultCode\":\"ORDER_999\",\"resultMessage\":\"원격 내부 메시지\"}}", StandardCharsets.UTF_8).build();
        return FeignException.errorStatus("OrderPickingClient#findAll(List)", response);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> adapter.findAll(List.of(1L))).isInstanceOfSatisfying(PickingException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(PickingErrorCode.SNAPSHOT_UNAVAILABLE);
            assertThat(error.getMessage()).isEqualTo(PickingErrorCode.SNAPSHOT_UNAVAILABLE.getMessage()).doesNotContain("원격");
        });
    }

    @Test
    @DisplayName("한 번의 벌크 호출로 읽고, 없는 주문을 지어내지 않으며, 국가 없음 · 빈 항목도 그대로 받는다")
    void mapsOneBulkCall() {
        OrderPickingSnapshotInternalResponse noCountry = new OrderPickingSnapshotInternalResponse("2", "PREPARING", null, List.of());
        given(client.findAll(List.of(1L, 2L, 3L))).willReturn(Response.success(List.of(snapshot("1"), noCountry)));

        List<PickingSnapshot> result = adapter.findAll(List.of(1L, 2L, 3L));

        assertThat(result).extracting(PickingSnapshot::orderId).containsExactly(1L, 2L);
        assertThat(result.getFirst().quantities()).containsExactlyEntriesOf(Map.of(10L, 5L));
        assertThat(result.get(1).countryCode()).isNull();
        assertThat(result.get(1).lines()).isEmpty();
        verify(client).findAll(List.of(1L, 2L, 3L));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("malformedResponses")
    @DisplayName("계약과 다른 응답(중복 · 요청 밖 · 범위 밖 아이디 · 상태 · 국가 · 항목 모양 · 실패 봉투)은 SNAPSHOT_UNAVAILABLE 이다")
    void rejectsMalformed(Response<List<OrderPickingSnapshotInternalResponse>> response) {
        given(client.findAll(List.of(1L))).willReturn(response);

        assertUnavailable();
    }

    static Stream<Response<List<OrderPickingSnapshotInternalResponse>>> malformedResponses() {
        return Stream.of(
            Response.success(List.of(snapshot("1"), snapshot("1"))),
            Response.success(List.of(snapshot("2"))),
            Response.success(List.of(snapshot("9223372036854775808"))),
            Response.success(List.of(snapshot("01"))),
            Response.success(List.of(new OrderPickingSnapshotInternalResponse("1", "preparing", "KR", List.of()))),
            Response.success(List.of(new OrderPickingSnapshotInternalResponse("1", null, "KR", List.of()))),
            Response.success(List.of(new OrderPickingSnapshotInternalResponse("1", "PREPARING", "kor", List.of()))),
            Response.success(List.of(new OrderPickingSnapshotInternalResponse("1", "PREPARING", "KR", null))),
            Response.success(List.of(new OrderPickingSnapshotInternalResponse("1", "PREPARING", "KR", List.of(new Line("10", 0L, false))))),
            Response.success(List.of(new OrderPickingSnapshotInternalResponse("1", "PREPARING", "KR", List.of(new Line("-1", 1L, false))))),
            Response.success(List.of(new OrderPickingSnapshotInternalResponse("1", "PREPARING", "KR", List.of(new Line("10", null, false))))),
            Response.success(null),
            Response.fail("ORDER_999", "원격 내부 메시지"),
            null);
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    @DisplayName("4xx · 5xx · 타임아웃은 SNAPSHOT_UNAVAILABLE 이고 원격 메시지를 싣지 않는다")
    void translatesTransportFailures(RuntimeException failure) {
        given(client.findAll(List.of(1L))).willThrow(failure);

        assertUnavailable();
    }

    static Stream<RuntimeException> transportFailures() {
        return Stream.of(status(400), status(403), status(500), status(503),
            new RetryableException(-1, "read timed out", Request.HttpMethod.GET, (Long) null, REQUEST));
    }

    @Test
    @DisplayName("서킷이 열려 있으면 부르지 않고 SNAPSHOT_UNAVAILABLE 이다")
    void circuitOpen() {
        registry.circuitBreaker("order-service").transitionToOpenState();

        assertUnavailable();
        verifyNoInteractions(client);
    }
}
