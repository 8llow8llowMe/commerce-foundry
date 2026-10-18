package com.commercefoundry.domainlayer.product.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.request.StockReservationRequest;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.StockReservationResponse;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.StockInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 재고 선점 · 확정 · 해제 — internal 쓰기 API 다 ({@code order-flow-guide.md} §3). 호출자는 order-service 하나다.
 *
 * <p>모두 멱등이다. 선점은 주문 아이디로(같은 주문 · 같은 항목이면 기존 선점), 확정 · 해제는 선점 행의 상태로 이미 처리됐는지 판단한다 —
 * 호출한 쪽이 타임아웃 뒤 같은 요청을 다시 보내도 재고가 두 번 움직이지 않는다. 신규 · 기존 모두 200 이다.
 *
 * <p>권한 · 검증 순서는 {@link SkuInternalController} 와 같다 — 서비스 토큰이 없거나 틀리면 internal 체인이 401({@code SECURITY_007}),
 * 인증을 통과한 뒤에는 메서드 검증이 {@code @PreAuthorize} 보다 먼저 돈다. 클래스에 {@code @Validated} 를 달지 않는다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/stock-reservations")
public class StockInternalController {

    private final StockInternalUseCase stockInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping
    public ResponseEntity<Response<StockReservationResponse>> reserve(@RequestBody @Valid StockReservationRequest request) {
        return ResponseEntity.ok().body(Response.success(stockInternalUseCase.reserve(request.toCommand())));
    }

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping("/{reservationId}/confirmation")
    public ResponseEntity<Response<StockReservationResponse>> confirm(
        @PathVariable @Positive(message = ProductValidationMessage.STOCK_RESERVATION_ID_POSITIVE) long reservationId
    ) {
        return ResponseEntity.ok().body(Response.success(stockInternalUseCase.confirm(reservationId)));
    }

    @PreAuthorize("hasAuthority('SERVICE_order-service')")
    @PostMapping("/{reservationId}/release")
    public ResponseEntity<Response<StockReservationResponse>> release(
        @PathVariable @Positive(message = ProductValidationMessage.STOCK_RESERVATION_ID_POSITIVE) long reservationId
    ) {
        return ResponseEntity.ok().body(Response.success(stockInternalUseCase.release(reservationId)));
    }
}
