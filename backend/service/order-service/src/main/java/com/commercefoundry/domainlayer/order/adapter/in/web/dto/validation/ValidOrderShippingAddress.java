package com.commercefoundry.domainlayer.order.adapter.in.web.dto.validation;

import com.commercefoundry.domainlayer.order.adapter.in.web.dto.request.ShippingAddressRequest;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 국가별 연락처와 해외 필수 도시를 도메인 조립 전에 검증한다. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidOrderShippingAddress.Validator.class)
public @interface ValidOrderShippingAddress {
    String message() default OrderValidationMessage.RECIPIENT_PHONE_FORMAT;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidOrderShippingAddress, ShippingAddressRequest> {
        @Override
        public boolean isValid(ShippingAddressRequest value, ConstraintValidatorContext context) {
            if (value == null || value.countryCode() == null || !value.countryCode().matches("[A-Z]{2}")) {
                return true;
            }
            boolean domestic = "KR".equals(value.countryCode());
            if (value.recipientPhone() != null && !value.recipientPhone().isBlank()) {
                String phone = value.recipientPhone().replaceAll("[\\s-]", "");
                if (!phone.matches(domestic ? "0[0-9]{8,10}" : "\\+?[0-9]{7,15}")) {
                    return reject(context, "recipientPhone", OrderValidationMessage.RECIPIENT_PHONE_FORMAT);
                }
            }
            if (!domestic && (value.city() == null || value.city().isBlank())) {
                return reject(context, "city", OrderValidationMessage.CITY_REQUIRED);
            }
            return true;
        }

        private static boolean reject(ConstraintValidatorContext context, String field, String message) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
            return false;
        }
    }
}
