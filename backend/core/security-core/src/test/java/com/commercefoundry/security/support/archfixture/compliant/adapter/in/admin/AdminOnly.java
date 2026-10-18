package com.commercefoundry.security.support.archfixture.compliant.adapter.in.admin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/** 합성 애노테이션 — 메타로 붙은 {@code @PreAuthorize} 도 메서드 보안 · 규칙 모두 같은 선언으로 본다. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@PreAuthorize("hasRole('ADMIN')")
public @interface AdminOnly {
}
