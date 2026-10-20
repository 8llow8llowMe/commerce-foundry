package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.validation;

import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressValidationMessage;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ValidShippingAddressRegion} 판정. 규칙 자체는 도메인({@link ShippingAddressContent})의 것을 그대로 부른다 — 요청 검증과 도메인 검증이 갈라지지 않게.
 *
 * <p>국가 코드가 비었거나 형식이 틀리면 아무것도 보지 않는다 — 그 오류는 {@code countryCode} 필드 제약이 낸다.
 * 연락처가 비었으면 형식을 보지 않는다 — 필수 오류(AUTH_122)는 필드 제약이 낸다.
 */
public class ShippingAddressRegionValidator implements ConstraintValidator<ValidShippingAddressRegion, ShippingAddressFields> {

    @Override
    public boolean isValid(ShippingAddressFields request, ConstraintValidatorContext context) {
        if (request == null || !ShippingAddressContent.isValidCountryCode(request.countryCode())) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean domestic = ShippingAddressContent.isDomestic(request.countryCode());
        boolean valid = true;

        String phone = request.recipientPhone();
        if (phone != null && !phone.isBlank() && !ShippingAddressContent.isValidPhone(request.countryCode(), phone)) {
            valid = reject(context, ShippingAddressValidationMessage.RECIPIENT_PHONE_FORMAT_INVALID, "recipientPhone");
        }

        String postalCode = stripToNull(request.postalCode());
        if (domestic && postalCode == null) {
            valid = reject(context, ShippingAddressValidationMessage.POSTAL_CODE_REQUIRED, "postalCode");
        } else if (domestic && !ShippingAddressContent.isValidDomesticPostalCode(postalCode)) {
            valid = reject(context, ShippingAddressValidationMessage.POSTAL_CODE_FORMAT_INVALID, "postalCode");
        } else if (!domestic && !ShippingAddressContent.isValidInternationalPostalCode(postalCode)) {
            valid = reject(context, ShippingAddressValidationMessage.POSTAL_CODE_FORMAT_INVALID, "postalCode");
        }

        if (!domestic && stripToNull(request.city()) == null) {
            valid = reject(context, ShippingAddressValidationMessage.CITY_REQUIRED, "city");
        }
        return valid;
    }

    /** 비거나 공백뿐인 선택 칸은 없는 값이다 — 도메인이 저장할 때와 같은 판정. */
    private static String stripToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** 메시지에 {@code {}} · {@code ${}} 가 없어 템플릿 보간이 바꾸는 것이 없다. */
    private static boolean reject(ConstraintValidatorContext context, String message, String field) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        return false;
    }
}
