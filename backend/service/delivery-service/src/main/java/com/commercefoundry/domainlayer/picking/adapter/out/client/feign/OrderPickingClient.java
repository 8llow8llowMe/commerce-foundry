package com.commercefoundry.domainlayer.picking.adapter.out.client.feign;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.picking.adapter.out.client.feign.dto.OrderPickingSnapshotInternalResponse;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * order-service internal 피킹용 주문 스냅숏 벌크 조회 (#363). 서비스 토큰을 싣는다({@link InternalFeignClientConfiguration}) — 사용자 {@code Authorization} 은
 * 넘기지 않는다 ({@code security-guide.md} §9-5). order 는 허용 호출자로 {@code SERVICE_delivery-service} 하나만 선언한다.
 *
 * <p>직접 부르지 않는다 — 서킷 · 예외 변환은 {@code OrderPickingInternalResponseSupport} 가 감싸고, application 에는 {@code OrderPickingSnapshotQueryPort} 로만 나간다.
 * 타임아웃(connect 2초 · read 3초)은 {@code application.yml} 의 openfeign {@code default} 설정이다.
 */
@FeignClient(
    name = "${feign-client.target-services.order-service:order-service}",
    contextId = "orderPickingClient",
    configuration = {InternalFeignClientConfiguration.class, OrderPickingClientConfiguration.class})
public interface OrderPickingClient {

    /** 최대 20건. 없는 주문은 결과에서 빠지고 항목 없는 주문은 {@code lines=[]} 로 온다. 받는 사람 · 주소 · 연락처는 싣지 않는다. */
    @GetMapping("/internal/v1/orders/picking-snapshots")
    Response<List<OrderPickingSnapshotInternalResponse>> findAll(@RequestParam("orderIds") List<Long> orderIds);
}
