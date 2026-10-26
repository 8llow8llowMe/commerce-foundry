package com.commercefoundry.domainlayer.notification.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.item.NotificationAdminItem;
import com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.response.NotificationUnreadCountResponse;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationValidationMessage;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.in.NotificationAdminUseCase;
import com.commercefoundry.global.properties.NotificationProperties;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 어드민 알림 센터 (#213). 파라미터 검증은 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다.
 * 스트림만 응답 봉투가 없다 ({@code api-design-guide.md} §7-2).
 */
@Tag(name = "[어드민] 알림", description = "작업 완료 · 실패와 운영 알림")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/notifications")
public class NotificationAdminController {

    private final NotificationAdminUseCase notificationAdminUseCase;
    private final NotificationProperties notificationProperties;

    @Operation(summary = "알림 목록", description = "내 알림을 최신순으로 본다. 없으면 빈 목록이다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<PageResponse<NotificationAdminItem>>> list(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = NotificationValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = NotificationValidationMessage.SIZE_RANGE) @Max(value = 100, message = NotificationValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().body(Response.success(notificationAdminUseCase.list(member.memberId(), page, size)));
    }

    @Operation(summary = "읽지 않은 알림 수")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/unread-count")
    public ResponseEntity<Response<NotificationUnreadCountResponse>> unreadCount(@AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().body(Response.success(notificationAdminUseCase.unreadCount(member.memberId())));
    }

    @Operation(summary = "알림 읽음", description = "이미 읽은 알림이면 같은 내용이다. 없거나 내 알림이 아니면 404(NOTIFICATION_001)다.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Response<NotificationAdminItem>> markRead(
        @AuthenticationPrincipal MemberPrincipal member,
        @Parameter(description = "알림 아이디") @PathVariable @Positive(message = NotificationValidationMessage.NOTIFICATION_ID_POSITIVE) long notificationId
    ) {
        return ResponseEntity.ok().body(Response.success(notificationAdminUseCase.markRead(notificationId, member.memberId(), Instant.now())));
    }

    @Operation(summary = "알림 모두 읽음", description = "처리 뒤 읽지 않은 수를 돌려준다.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/read-all")
    public ResponseEntity<Response<NotificationUnreadCountResponse>> markAllRead(@AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().body(Response.success(notificationAdminUseCase.markAllRead(member.memberId(), Instant.now())));
    }

    @Operation(summary = "알림 스트림", description = "이벤트 이름 notification. 끊기면 목록으로 따라잡고 다시 연다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal MemberPrincipal member) {
        SseEmitter emitter = new SseEmitter(notificationProperties.streamTimeout().toMillis());
        NotificationStream stream = message -> emitter.send(SseEmitter.event().name("notification").data(message, MediaType.APPLICATION_JSON));
        Runnable close = () -> notificationAdminUseCase.closeStream(member.memberId(), stream);
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        notificationAdminUseCase.openStream(member.memberId(), stream);
        return emitter;
    }
}
