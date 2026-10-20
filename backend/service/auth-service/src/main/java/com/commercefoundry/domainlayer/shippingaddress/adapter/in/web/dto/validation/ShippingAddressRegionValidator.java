package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.validation;

import com.commercefoundry.domainlayer.shippingaddress.application.exception.ShippingAddressValidationMessage;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ValidShippingAddressRegion} 판정. 규칙 자체는 도메인({@link ShippingAddressContent})의 것을 그대로 부른다 — 요청 검증과 도메인 검증이 갈라지지 않게.
 *
 * <p><b>필수 칸의 유니코드 공백</b> — {@code @NotBlank} 는 {@code trim()}(U+0020 이하) 기준이라 전각 공백({@code U+3000}) 같은 값만 있는 칸을 통과시킨다.
 * 도메인은 {@code strip()}(유니코드 공백) 기준으로 비었다고 보므로, 그대로 두면 도메인 검증에서 500 이 된다. 그래서 받는 사람 · 연락처 · 주소는
 * {@code @NotBlank} 가 통과시킨 값을 도메인과 같은 {@code strip()} 기준으로 다시 보고 같은 필수 코드(121 · 122 · 127)로 거절한다.
 * {@code @NotBlank} 가 이미 거절한 값은 다시 보지 않는다 — 같은 오류가 두 번 나가지 않게.
 *
 * <p>국가 코드가 비었거나 형식이 틀리면 국가별 규칙(연락처 형식 · 우편번호 · 도시)은 보지 않는다 — 그 오류는 {@code countryCode} 필드 제약이 낸다.
 * 연락처가 비었으면 형식을 보지 않는다 — 필수 오류(AUTH_122)가 따로 난다.
 */
public class ShippingAddressRegionValidator implements ConstraintValidator<ValidShippingAddressRegion, ShippingAddressFields> {

    @Override
    public boolean isValid(ShippingAddressFields request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;
        if (onlyUnicodeWhitespace(request.recipientName())) {
            valid = reject(context, ShippingAddressValidationMessage.RECIPIENT_NAME_INVALID, "recipientName");
        }
        String phone = request.recipientPhone();
        boolean phoneBlank = phone == null || phone.isBlank();
        if (onlyUnicodeWhitespace(phone)) {
            valid = reject(context, ShippingAddressValidationMessage.RECIPIENT_PHONE_REQUIRED, "recipientPhone");
        }
        if (onlyUnicodeWhitespace(request.addressLine1())) {
            valid = reject(context, ShippingAddressValidationMessage.ADDRESS_LINE1_REQUIRED, "addressLine1");
        }
        if (!ShippingAddressContent.isValidCountryCode(request.countryCode())) {
            return valid;
        }
        boolean domestic = ShippingAddressContent.isDomestic(request.countryCode());

        if (!phoneBlank && !ShippingAddressContent.isValidPhone(request.countryCode(), phone)) {
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

    /** {@code @NotBlank}(trim 기준)는 통과했지만 도메인(strip 기준)으로는 빈 값 — 전각 공백 등 유니코드 공백만 있다. */
    private static boolean onlyUnicodeWhitespace(String value) {
        return value != null && !value.trim().isEmpty() && value.strip().isEmpty();
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
