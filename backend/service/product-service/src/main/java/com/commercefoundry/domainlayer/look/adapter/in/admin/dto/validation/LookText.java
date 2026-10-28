package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 룩 문구에 제어 문자({@code \p{Cc}})와 짝 없는 서로게이트가 없다. {@link #multiline()} 이면 개행(CR · LF) · 탭만 허용한다(설명).
 * null 은 통과한다. 판정은 {@link LookTextValidator} 다 (메인 전시 {@code DisplayText} 와 같은 규칙 — 컨텍스트 간 adapter 를 공유하지 않아 따로 둔다).
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = LookTextValidator.class)
public @interface LookText {

    String message();

    /** 개행(CR · LF) · 탭을 허용하는가. 설명만 true 다. */
    boolean multiline() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
