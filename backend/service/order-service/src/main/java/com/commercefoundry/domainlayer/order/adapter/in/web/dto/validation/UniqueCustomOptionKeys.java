package com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.request.CustomOptionRequest;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 맞춤 제작 입력 목록의 fieldKey 가 겹치지 않는다. fieldKey 는 형식 제약(영문 · 숫자 · 밑줄)이 있어 정규화 없이 그대로 비교한다.
 * null 목록 · null 원소 · null fieldKey 는 다른 제약이 본다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UniqueCustomOptionKeys.Validator.class)
public @interface UniqueCustomOptionKeys {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<UniqueCustomOptionKeys, List<CustomOptionRequest>> {

        @Override
        public boolean isValid(List<CustomOptionRequest> customOptions, ConstraintValidatorContext context) {
            if (customOptions == null) {
                return true;
            }
            Set<String> fieldKeys = new HashSet<>();
            for (CustomOptionRequest option : customOptions) {
                if (option != null && option.fieldKey() != null && !fieldKeys.add(option.fieldKey())) {
                    return false;
                }
            }
            return true;
        }
    }
}
