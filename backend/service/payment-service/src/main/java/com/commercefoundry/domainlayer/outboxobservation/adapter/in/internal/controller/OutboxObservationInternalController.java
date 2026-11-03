package com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.outboxobservation.adapter.in.internal.dto.response.OutboxObservationResponse;
import com.commercefoundry.domainlayer.outboxobservation.application.port.in.OutboxObservationInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/outbox-observations")
public class OutboxObservationInternalController {

    private final OutboxObservationInternalUseCase useCase;

    @GetMapping("/ready")
    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    public ResponseEntity<Response<OutboxObservationResponse>> observeReady() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.observeReady()));
    }
}
