package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.validation;

import com.commercefoundry.common.validation.ConstraintPriority;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 필수 문자열 — null 이 아니고 {@code strip()} 뒤에도 비어 있지 않다. 요청 DTO 의 정규화({@code TextValues.normalize}, {@code strip()})와 같은 기준이다.
 *
 * <p>{@code @NotBlank} 는 {@code trim()} 기준이라 전각 공백(U+3000) 같은 유니코드 공백만 담은 값을 통과시키고, 정규화가 그 값을 null 로 바꿔
 * 명령 생성에서 NPE(500)가 난다 — 판정과 정규화가 같은 기준을 쓰게 이 제약을 둔다. 자리는 필수(정렬에서 길이 · 형식보다 앞)다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@ConstraintPriority(ConstraintPriority.Level.REQUIRED)
@Constraint(validatedBy = DisplayRequiredTextValidator.class)
public @interface DisplayRequiredText {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
