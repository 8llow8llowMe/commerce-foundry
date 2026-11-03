package com.commercefoundry.domainlayer.member.adapter.in.internal.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.AdminNotificationRecipientInternalResponse;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import com.commercefoundry.domainlayer.member.application.port.in.AdminNotificationRecipientInternalUseCase;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 시스템 알림 수신자 조회 (#477). 사용자 토큰은 internal 체인이 거부한다. */
@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/admin-notification-recipients")
public class AdminNotificationRecipientInternalController {

    private final AdminNotificationRecipientInternalUseCase adminNotificationRecipientInternalUseCase;

    @PreAuthorize("hasAuthority('SERVICE_operations-service')")
    @GetMapping
    public ResponseEntity<Response<AdminNotificationRecipientInternalResponse>> list(
        @RequestParam(defaultValue = "0")
        @PositiveOrZero(message = MemberValidationMessage.NOTIFICATION_RECIPIENT_AFTER_ID_MIN) long afterId,
        @RequestParam(defaultValue = "100")
        @Min(value = 1, message = MemberValidationMessage.NOTIFICATION_RECIPIENT_SIZE_RANGE)
        @Max(value = 100, message = MemberValidationMessage.NOTIFICATION_RECIPIENT_SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok(Response.success(adminNotificationRecipientInternalUseCase.list(afterId, size)));
    }
}
