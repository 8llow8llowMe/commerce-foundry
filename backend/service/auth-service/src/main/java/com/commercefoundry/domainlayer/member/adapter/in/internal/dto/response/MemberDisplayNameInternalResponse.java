package com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item.MemberDisplayNameInternalItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "서비스 간 회원 표시 이름 벌크 조회 응답. 원문 이름은 없다")
public record MemberDisplayNameInternalResponse(

    @Schema(description = "요청 순서(중복 제거 후)의 회원. 없는 아이디는 빠진다. 이름이 없으면 displayName 은 null")
    List<MemberDisplayNameInternalItem> members
) {
}
