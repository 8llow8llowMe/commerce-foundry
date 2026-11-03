package com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 계정명 · 이메일 등 수신자 개인정보를 포함하지 않는다. */
@Schema(description = "시스템 알림을 받을 활성 관리자 아이디 페이지")
public record AdminNotificationRecipientInternalResponse(
    @Schema(description = "숫자 아이디 오름차순. 다음 afterId는 마지막 아이디이며, size 미만이면 끝이다")
    List<String> memberIds
) {
}
