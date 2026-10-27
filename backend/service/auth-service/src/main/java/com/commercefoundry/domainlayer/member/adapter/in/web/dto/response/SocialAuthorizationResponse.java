package com.commercefoundry.domainlayer.member.adapter.in.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "소셜 로그인 시작. state와 verifier는 같은 탭 sessionStorage에 보관한다")
public record SocialAuthorizationResponse(

    @Schema(description = "제공자 인가 URL — verifier는 포함하지 않는다")
    String authorizeUrl,

    @Schema(description = "콜백에서 대조할 일회용 state")
    String state,

    @Schema(description = "브라우저 바인딩 비밀. URL · 로그에 넣지 않는다")
    String verifier
) {
    @Override
    public String toString() {
        return "SocialAuthorizationResponse[redacted]";
    }
}