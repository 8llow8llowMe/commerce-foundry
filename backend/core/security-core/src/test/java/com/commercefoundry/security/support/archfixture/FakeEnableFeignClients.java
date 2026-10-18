package com.commercefoundry.security.support.archfixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code @EnableFeignClients} 대역. security-core 테스트 클래스패스에 OpenFeign(spring-cloud-openfeign-core)을 넣으면 Feign 자동설정이
 * 통합 테스트 컨텍스트에 끼어들어, 같은 모양(속성 {@code defaultConfiguration})의 애노테이션으로 규칙을 시험한다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FakeEnableFeignClients {

    Class<?>[] defaultConfiguration() default {};
}
