package com.commercefoundry.security.support.archfixture.violating.config;

import com.commercefoundry.security.support.archfixture.FakeEnableFeignClients;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 위반 예시 — {@code defaultConfiguration} 을 품은 합성 애노테이션. 이것을 단 클래스는 직접 쓰지 않아도 전역 설정을 붙인다. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@FakeEnableFeignClients(defaultConfiguration = GlobalFeignConfig.class)
public @interface EnableAllFeignClients {
}
