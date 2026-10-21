package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.validation;

import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ValidCategoryName} 판정. 한 값에 위반 하나만 낸다 — 필수(132) → 문자(138) → 길이(133) 순이다.
 *
 * <ul>
 *   <li><b>필수</b> — null 이거나, {@code strip()}(유니코드 공백 — 전각 공백 U+3000 포함)한 뒤 서식 문자(제로폭 공백 U+200B 등 {@code \p{Cf}})를 빼면 비는 값.
 *       {@code @NotBlank} 는 {@code trim()}(U+0020 이하) 기준이라 이런 값을 통과시킨다 (선례: auth {@code ShippingAddressRegionValidator}, #7).</li>
 *   <li><b>문자</b> — 제어 문자({@code \p{Cc}} — 개행 · 탭 · NUL 포함)와 서식 문자({@code \p{Cf}})는 어디에 있든 거절한다. 메뉴에 그대로 찍히거나 보이지 않는 글자가 된다.</li>
 *   <li><b>길이</b> — 저장하는 값(앞뒤 공백을 뺀 값)의 코드 포인트 수로 50 이하. 컬럼 {@code varchar(50)} 과 같은 기준이다.</li>
 * </ul>
 */
public class CategoryNameValidator implements ConstraintValidator<ValidCategoryName, String> {

    private static final int MAX_LENGTH = 50;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        String violation = violationOf(value);
        if (violation == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(violation).addConstraintViolation();
        return false;
    }

    /** 메시지에 {@code {}} · {@code ${}} 가 없어 템플릿 보간이 바꾸는 것이 없다. */
    private static String violationOf(String value) {
        if (value == null) {
            return ProductValidationMessage.CATEGORY_NAME_REQUIRED;
        }
        String stripped = value.strip();
        if (stripped.codePoints().allMatch(CategoryNameValidator::isFormat)) {
            return ProductValidationMessage.CATEGORY_NAME_REQUIRED;
        }
        if (value.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL || isFormat(codePoint))) {
            return ProductValidationMessage.CATEGORY_NAME_INVALID_CHARACTER;
        }
        if (stripped.codePointCount(0, stripped.length()) > MAX_LENGTH) {
            return ProductValidationMessage.CATEGORY_NAME_SIZE_MAX;
        }
        return null;
    }

    private static boolean isFormat(int codePoint) {
        return Character.getType(codePoint) == Character.FORMAT;
    }
}
