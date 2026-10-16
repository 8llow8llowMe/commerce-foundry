package com.commercefoundry.security.internal;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * 검증을 마친 서비스 토큰을 {@link ServicePrincipal} 인증으로 바꾼다. 권한은 {@code SERVICE_<iss>} 하나다.
 *
 * <p>claim 모양은 디코더의 validator 가 먼저 막는다. 여기서 다시 실패하면 validator 와 어긋난 것이므로
 * 500 이 아니라 401(007)이 되게 {@link InvalidBearerTokenException} 을 던진다.
 *
 * <p>스프링 빈으로 등록하지 않는다 — {@code Converter} 빈은 MVC 의 변환 서비스에도 등록된다.
 */
public final class ServiceJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String serviceId = jwt.getClaimAsString(JwtClaimNames.ISS);
        String tokenId = jwt.getId();
        if (!InternalTokenFormat.isServiceId(serviceId) || tokenId == null || tokenId.isBlank()) {
            throw new InvalidBearerTokenException("service token claims are malformed");
        }
        ServicePrincipal principal = new ServicePrincipal(serviceId, tokenId);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(new SimpleGrantedAuthority(principal.authority())));
    }
}
