package com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** 읽지 않은 알림 수 (#213). */
@Builder
@Schema(description = "읽지 않은 알림 수")
public record NotificationUnreadCountResponse(

    @Schema(description = "읽지 않은 알림 수", example = "2")
    long count
) {
}
