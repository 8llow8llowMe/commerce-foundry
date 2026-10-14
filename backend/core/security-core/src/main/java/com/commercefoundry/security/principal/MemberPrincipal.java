package com.commercefoundry.security.principal;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * 검증된 access 토큰의 주인. 컨트롤러는 {@code @AuthenticationPrincipal MemberPrincipal member} 로 받고
 * {@code member.memberId()} 만 UseCase 로 넘긴다 ({@code security-guide.md}).
 *
 * @param memberId 회원 아이디 ({@code sub})
 * @param role     역할 ({@code role})
 * @param tokenId  토큰 아이디 ({@code jti}). 로그아웃 블랙리스트(#4)와 로그 상관관계에 쓴다
 */
public record MemberPrincipal(
    long memberId,
    MemberRole role,
    String tokenId
) implements AuthenticatedPrincipal {

    /** Spring Security 의 {@code Authentication#getName()} 이 이 값을 쓴다. */
    @Override
    public String getName() {
        return String.valueOf(memberId);
    }
}
