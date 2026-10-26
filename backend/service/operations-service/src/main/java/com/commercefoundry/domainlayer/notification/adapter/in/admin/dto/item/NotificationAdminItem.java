package com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

/** 어드민 알림 한 건. 제목 · 링크에 고객 개인정보 원문은 없다 (#213). */
@Builder
@Schema(description = "어드민 알림")
public record NotificationAdminItem(

    @Schema(description = "알림 아이디", example = "4611686018427387904")
    String notificationId,

    @Schema(description = "종류")
    CodeNameDescriptionMetadata kind,

    @Schema(description = "심각도")
    CodeNameDescriptionMetadata severity,

    @Schema(description = "제목", example = "작업이 끝났습니다")
    String title,

    @Schema(description = "어드민 화면 경로. 없으면 null", example = "/admin/jobs/4611686018427387904", nullable = true)
    String linkPath,

    @Schema(description = "읽었는지", example = "false")
    boolean read,

    @Schema(description = "생긴 시각 (UTC)", example = "2026-10-26T01:00:00Z")
    Instant createdAt
) {
}
