package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.request;

import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryValidationMessage;
import com.commercefoundry.shared.commerce.ShippingScope;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * {@link ValidQuoteDestination} 판정. 국가 코드가 비었거나 형식이 틀리면 아무것도 보지 않는다 — 그 오류는 {@code countryCode} 필드 제약이 낸다.
 */
public class QuoteDestinationValidator implements ConstraintValidator<ValidQuoteDestination, ShippingFeeQuoteInternalRequest> {

    private static final Pattern DOMESTIC_POSTAL_CODE = Pattern.compile("^[0-9]{5}$");
    /** 해외 우편번호는 나라마다 모양이 달라 문자 종류 · 길이만 본다 (예: {@code 10001}, {@code SW1A 1AA}, {@code 100-0001}). */
    private static final Pattern INTERNATIONAL_POSTAL_CODE = Pattern.compile("^[A-Za-z0-9 -]{1,20}$");

    @Override
    public boolean isValid(ShippingFeeQuoteInternalRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        ShippingScope scope = ShippingScope.ofCountry(request.countryCode()).orElse(null);
        if (scope == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        return scope == ShippingScope.DOMESTIC ? validateDomestic(request, context) : validateInternational(request, context);
    }

    private static boolean validateDomestic(ShippingFeeQuoteInternalRequest request, ConstraintValidatorContext context) {
        String postalCode = request.postalCode();
        if (postalCode == null || postalCode.isBlank()) {
            return reject(context, DeliveryValidationMessage.POSTAL_CODE_REQUIRED, "postalCode");
        }
        if (!DOMESTIC_POSTAL_CODE.matcher(postalCode).matches()) {
            return reject(context, DeliveryValidationMessage.POSTAL_CODE_FORMAT, "postalCode");
        }
        return true;
    }

    /** 해외 우편번호는 선택이다 — null · 빈 문자열 · 공백뿐인 값은 없는 값으로 보고, 값이 있을 때만 형식을 본다. */
    private static boolean validateInternational(ShippingFeeQuoteInternalRequest request, ConstraintValidatorContext context) {
        boolean valid = true;
        String postalCode = request.postalCode();
        if (postalCode != null && !postalCode.isBlank() && !INTERNATIONAL_POSTAL_CODE.matcher(postalCode).matches()) {
            valid = reject(context, DeliveryValidationMessage.INTERNATIONAL_POSTAL_CODE_FORMAT, "postalCode");
        }
        if (request.weightGrams() == null) {
            valid = reject(context, DeliveryValidationMessage.WEIGHT_GRAMS_REQUIRED, "weightGrams");
        } else if (request.weightGrams() < 1) {
            valid = reject(context, DeliveryValidationMessage.WEIGHT_GRAMS_POSITIVE, "weightGrams");
        }
        return valid;
    }

    /** 메시지에 {@code {}} · {@code ${}} 가 없어 템플릿 보간이 바꾸는 것이 없다. */
    private static boolean reject(ConstraintValidatorContext context, String message, String field) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
        return false;
    }
}
