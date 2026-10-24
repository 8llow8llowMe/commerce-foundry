package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 문자열에 제어 문자({@code \p{Cc}} — C0 · DEL · C1, NUL · 개행 · 탭 포함)와 짝 없는 서로게이트가 없다. 앞뒤 공백을 자르기 전의 원문 기준이다.
 * null 은 통과한다(필수는 {@code @NotBlank} 몫). 판정은 {@link NoControlCharactersValidator} 다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NoControlCharactersValidator.class)
public @interface NoControlCharacters {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
