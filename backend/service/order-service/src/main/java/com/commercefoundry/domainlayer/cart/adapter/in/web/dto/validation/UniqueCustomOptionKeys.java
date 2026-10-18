package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 맞춤 제작 입력 목록의 fieldKey 가 겹치지 않는다. null 목록 · null 원소 · null fieldKey 는 다른 제약이 본다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UniqueCustomOptionKeysValidator.class)
public @interface UniqueCustomOptionKeys {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
