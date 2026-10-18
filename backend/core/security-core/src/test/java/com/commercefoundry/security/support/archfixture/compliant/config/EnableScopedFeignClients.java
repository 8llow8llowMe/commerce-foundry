package com.commercefoundry.security.support.archfixture.compliant.config;

import com.commercefoundry.security.support.archfixture.FakeEnableFeignClients;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 준수 예시 — {@code defaultConfiguration} 없이 Feign 활성화를 감싼 합성 애노테이션. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@FakeEnableFeignClients
public @interface EnableScopedFeignClients {
}
