package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link SizeChartText} 판정. 필수 칸은 필수 → 문자 → 길이, 선택 칸은 null · 공백만이면 통과하고 문자가 있으면 문자 → 길이다.
 * {@code @}{@code NotBlank} 는 {@code trim()} 기준이라 전각 공백을 통과시키므로 쓰지 않는다.
 */
public class SizeChartTextValidator implements ConstraintValidator<SizeChartText, String> {

    private boolean required;
    private String requiredMessage;
    private String invalidCharacterMessage;
    private String tooLongMessage;
    private int maxCodePoints;

    @Override
    public void initialize(SizeChartText annotation) {
        this.required = annotation.required();
        this.requiredMessage = annotation.requiredMessage();
        this.invalidCharacterMessage = annotation.invalidCharacterMessage();
        this.tooLongMessage = annotation.tooLongMessage();
        this.maxCodePoints = annotation.maxCodePoints();
    }

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

    private String violationOf(String value) {
        if (value == null) {
            return required ? requiredMessage : null;
        }
        String stripped = value.strip();
        if (required && stripped.codePoints().allMatch(SizeChartTextValidator::isFormat)) {
            return requiredMessage;
        }
        if (value.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL || isFormat(codePoint))) {
            return invalidCharacterMessage;
        }
        if (stripped.codePointCount(0, stripped.length()) > maxCodePoints) {
            return tooLongMessage;
        }
        return null;
    }

    private static boolean isFormat(int codePoint) {
        return Character.getType(codePoint) == Character.FORMAT;
    }
}
