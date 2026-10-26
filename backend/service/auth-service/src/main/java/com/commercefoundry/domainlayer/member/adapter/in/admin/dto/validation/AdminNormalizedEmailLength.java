package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.validation;

import com.commercefoundry.common.validation.ConstraintPriority;
import com.commercefoundry.domainlayer.member.application.exception.MemberValidationMessage;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 저장하는 형태({@code Member.canonicalEmail})로 바꾼 뒤에도 이메일이 254자 이하다.
 * 고객 가입의 같은 규칙(#233)을 가게 운영자 추가에 둔다. 진입 패키지를 나누려고 어노테이션은 따로다.
 */
@ConstraintPriority(ConstraintPriority.Level.LENGTH)
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AdminNormalizedEmailLengthValidator.class)
public @interface AdminNormalizedEmailLength {

    String message() default MemberValidationMessage.ADMIN_EMAIL_LENGTH_INVALID;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
