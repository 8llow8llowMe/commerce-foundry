package com.commercefoundry.security.support;

import com.commercefoundry.security.internal.InternalTokenFormat;
import com.commercefoundry.security.internal.ServicePrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 인증 스모크 전용 internal 핸들러 (테스트 클래스패스에만 있다). 허용 호출자를 {@value #ALLOWED_CALLER} 하나로 고정해
 * 허용 목록 거부(403 · {@code SECURITY_005})와 통과(200)를 서비스 컨트롤러 없이 본다.
 * {@link InternalSecuritySmokeContextCustomizerFactory} 가 서블릿 테스트 컨텍스트마다 등록한다 — 컴포넌트 스캔 대상이 아니다.
 *
 * <p>실제 internal 컨트롤러도 이렇게 메서드마다 허용 호출자를 선언한다 ({@code security-guide.md} §9).
 */
@RestController
public class InternalSecuritySmokeController {

    public static final String PATH = "/internal/v1/__security-smoke";
    /** 이 핸들러가 허용하는 유일한 호출 서비스. 실제 서비스 ID 와 겹치지 않는 이름이다. */
    public static final String ALLOWED_CALLER = "smoke-allowed-caller";
    /** 허용 목록에 없는 호출 서비스. */
    public static final String DENIED_CALLER = "smoke-denied-caller";

    /** 응답 본문은 인증 주체의 호출 서비스 ID 다 — {@code @AuthenticationPrincipal ServicePrincipal} 이 풀리는지까지 본다. */
    @PreAuthorize("hasAuthority('" + InternalTokenFormat.AUTHORITY_PREFIX + ALLOWED_CALLER + "')")
    @GetMapping(PATH)
    public String caller(@AuthenticationPrincipal ServicePrincipal caller) {
        return caller.serviceId();
    }
}
