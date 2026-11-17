package com.commercefoundry.domainlayer.order.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.order.adapter.in.internal.dto.response.OrderPickingInternalResponse;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPickingInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/orders")
public class OrderPickingInternalController {
    private final OrderPickingInternalUseCase useCase;

    @PreAuthorize("hasAuthority('SERVICE_delivery-service')")
    @GetMapping("/picking-snapshots")
    public ResponseEntity<Response<List<OrderPickingInternalResponse>>> findAll(@RequestParam List<Long> orderIds) {
        return ResponseEntity.ok(Response.success(useCase.findAll(orderIds)));
    }
}
