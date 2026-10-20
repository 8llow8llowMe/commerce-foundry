package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.request.StockRestorationRequest;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockRestorationResponse;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 결제 후 재고 복원 — internal 쓰기 API 다 ({@code order-flow-guide.md} §3). 호출자는 order-service 하나다.
 *
 * <p>클레임 아이디로 멱등이다 — 같은 클레임 · 같은 내용의 재요청은 재고를 다시 늘리지 않고 처음 결과를 200 으로 돌려준다.
 * 결제 전 취소 · 만료는 이 경로가 아니라 선점 해제({@code /internal/v1/stock-reservations/{reservationId}/release})다.
 *
 * <p>권한 · 검증 순서는 {@link StockInternalController} 와 같다. 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/stock-restorations")
public class StockRestorationInternalController {

    private final StockInternalUseCase stockInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping
    public ResponseEntity<Response<StockRestorationResponse>> restore(@RequestBody @Valid StockRestorationRequest request) {
        return ResponseEntity.ok().body(Response.success(stockInternalUseCase.restore(request.toCommand())));
    }
}
