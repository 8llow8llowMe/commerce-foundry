package com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/** 서비스 간 회원 표시 이름 한 건 (#322). 원문 이름은 싣지 않는다. */
@Builder
@Schema(description = "서비스 간 회원 표시 이름 한 건. 이름은 마스킹된 값만 있다")
public record MemberDisplayNameInternalItem(

    @Schema(description = "회원 아이디", example = "4611686018427387904")
    String memberId,

    @Schema(description = "마스킹한 이름. 회원은 있으나 이름이 없으면 null", example = "홍*동", nullable = true)
    String displayName
) {
}
