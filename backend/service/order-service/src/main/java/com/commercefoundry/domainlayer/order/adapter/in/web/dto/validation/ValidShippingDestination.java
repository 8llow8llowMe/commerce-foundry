package com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation;

import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

/**
 * 우편번호가 국가에 맞다 — 국내(KR)는 필수 · 숫자 5자리, 해외는 선택이고 있으면 영문 · 숫자 · 공백 · 하이픈 20자 이하. 클래스 수준 제약이고 오류는
 * {@code postalCode} 필드로 나간다. delivery 의 견적 본문 규칙보다 느슨하면 고객 입력 오류가 503 으로 보이므로 같은 규칙을 먼저 본다
 * ({@code ShippingFeeQuoteQuery}). 국가가 비었거나 형식이 틀리면 그 필드의 제약이 보고 여기서는 건너뛴다.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidShippingDestination.Validator.class)
public @interface ValidShippingDestination {

    String message() default OrderValidationMessage.POSTAL_CODE_FORMAT;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** 국가 · 우편번호 칸을 가진 요청 DTO. */
    interface Fields {

        String countryCode();

        String postalCode();

        /** 계산 · 저장에 쓰는 우편번호 — 앞뒤 공백을 자르고, 비었으면(해외 선택) null 이다. 검증을 지난 뒤에만 부른다. */
        default String normalizedPostalCode() {
            String postalCode = postalCode();
            return postalCode == null || postalCode.isBlank() ? null : postalCode.strip();
        }
    }

    class Validator implements ConstraintValidator<ValidShippingDestination, Fields> {

        private static final String DOMESTIC = "KR";
        private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");
        private static final Pattern DOMESTIC_POSTAL_CODE = Pattern.compile("^[0-9]{5}$");
        private static final Pattern INTERNATIONAL_POSTAL_CODE = Pattern.compile("^[A-Za-z0-9 -]{1,20}$");

        @Override
        public boolean isValid(Fields destination, ConstraintValidatorContext context) {
            if (destination == null || destination.countryCode() == null || !COUNTRY_CODE.matcher(destination.countryCode()).matches()) {
                return true;
            }
            String postalCode = destination.postalCode();
            boolean blank = postalCode == null || postalCode.isBlank();
            if (DOMESTIC.equals(destination.countryCode())) {
                if (blank) {
                    return reject(context, OrderValidationMessage.POSTAL_CODE_REQUIRED);
                }
                return DOMESTIC_POSTAL_CODE.matcher(postalCode).matches() || reject(context, OrderValidationMessage.POSTAL_CODE_FORMAT);
            }
            return blank || INTERNATIONAL_POSTAL_CODE.matcher(postalCode).matches() || reject(context, OrderValidationMessage.POSTAL_CODE_FORMAT);
        }

        private static boolean reject(ConstraintValidatorContext context, String message) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message).addPropertyNode("postalCode").addConstraintViolation();
            return false;
        }
    }
}
