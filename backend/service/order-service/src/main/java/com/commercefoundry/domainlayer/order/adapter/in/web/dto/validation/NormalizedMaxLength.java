package com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.text.Normalizer;

/**
 * 앞뒤 공백을 자르고 Unicode NFC 로 정규화한 <b>저장될 값</b>의 길이가 {@link #max()} 이하다. 원문 길이({@code @Size})만 보면 NFC 로 늘어나는 문자가
 * 컬럼 길이를 넘겨 저장에서 500 이 된다. null 은 다른 제약이 본다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NormalizedMaxLength.Validator.class)
public @interface NormalizedMaxLength {

    int max();

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NormalizedMaxLength, String> {

        private int max;

        @Override
        public void initialize(NormalizedMaxLength annotation) {
            this.max = annotation.max();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || Normalizer.normalize(value.strip(), Normalizer.Form.NFC).length() <= max;
        }
    }
}
