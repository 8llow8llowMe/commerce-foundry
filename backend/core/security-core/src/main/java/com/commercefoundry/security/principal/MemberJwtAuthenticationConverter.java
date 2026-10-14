package com.commercefoundry.security.principal;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * 검증을 마친 {@link Jwt} 를 {@link MemberPrincipal} 인증으로 바꾼다. 게이트웨이(#4)는
 * {@code ReactiveJwtAuthenticationConverterAdapter} 로 감싸 쓴다.
 *
 * <p>claim 모양은 디코더의 validator 가 먼저 막는다. 여기서 다시 실패하면 validator 와 어긋난 것이므로
 * 500 이 아니라 401(003)이 되게 {@link InvalidBearerTokenException} 을 던진다.
 *
 * <p>스프링 빈으로 등록하지 않는다 — {@code Converter} 빈은 MVC 의 변환 서비스에도 등록된다.
 */
public final class MemberJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        MemberPrincipal principal = toPrincipal(jwt);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority(principal.role().authority())));
    }

    private static MemberPrincipal toPrincipal(Jwt jwt) {
        try {
            long memberId = Long.parseLong(jwt.getSubject());
            MemberRole role = MemberRole.fromClaim(jwt.getClaimAsString(MemberRole.CLAIM_NAME));
            return new MemberPrincipal(memberId, role, jwt.getId());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidBearerTokenException("access token claims are malformed");
        }
    }
}
