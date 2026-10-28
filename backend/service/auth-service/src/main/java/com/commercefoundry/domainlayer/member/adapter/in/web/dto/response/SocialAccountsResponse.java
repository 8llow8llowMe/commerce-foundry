package com.commercefoundry.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "연결된 소셜 제공자 목록")
public record SocialAccountsResponse(
    @Schema(description = "연결된 제공자 제어 코드. FE가 이름과 아이콘을 매핑한다", allowableValues = {"kakao", "naver"})
    List<String> providers
) {
}
