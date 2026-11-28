package com.commercefoundry.domainlayer.settlement.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.settlement.adapter.in.internal.dto.request.SettlementReconcileRequest;
import com.commercefoundry.domainlayer.settlement.adapter.in.internal.dto.response.SettlementReconcileResponse;
import com.commercefoundry.domainlayer.settlement.application.command.SettlementReconcileCommand;
import com.commercefoundry.domainlayer.settlement.application.port.in.SettlementReconcileInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 정산 수집 · 대사 (#219, {@code payment-guide.md} §10) — operations 정기 작업(PR2)만 부른다. 같은 매출일을 몇 번 불러도 결과가 같다(멱등).
 * PG 조회가 페이지마다 최대 60초라 호출자의 read timeout 은 그보다 길어야 한다.
 */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/settlements")
public class SettlementReconcileInternalController {

    private final SettlementReconcileInternalUseCase useCase;

    @PostMapping("/reconcile")
    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    public ResponseEntity<Response<SettlementReconcileResponse>> reconcile(@RequestBody @Valid SettlementReconcileRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.reconcile(new SettlementReconcileCommand(request.soldDate()))));
    }
}
