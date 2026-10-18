package com.commercefoundry.security.support.archfixture.violating.config;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 위반 예시 — 현재 요청의 사용자 토큰을 꺼내 internal 호출에 실으려는 코드. */
public class UserTokenRelay {

    public String currentAuthorization() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attributes.getRequest().getHeader("Authorization");
    }
}
