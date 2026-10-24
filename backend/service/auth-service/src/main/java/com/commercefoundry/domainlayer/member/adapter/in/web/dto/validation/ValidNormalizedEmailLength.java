package com.commercefoundry.domainlayer.member.adapter.in.web.dto.validation;

import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 저장하는 형태({@code Member.canonicalEmail} — 앞뒤 공백 제거 + 소문자)로 바꾼 뒤에도 이메일이 254자 이하다.
 * 소문자화로 길어지는 글자(U+0130 {@code İ} → {@code i̇}, 2글자)가 원문 길이만 보는 {@code @Size} 를 지나 도메인에서 500 이 되던 것을 막는다 (#233).
 * 판정은 {@link NormalizedEmailLengthValidator}. 원문 길이 · 필수는 {@code @Size} · {@code @NotBlank} 몫이라 같이 단다.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NormalizedEmailLengthValidator.class)
public @interface ValidNormalizedEmailLength {

    String message() default MemberValidationMessage.EMAIL_LENGTH_INVALID;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
