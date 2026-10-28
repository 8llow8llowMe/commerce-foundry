package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link DisplayRequiredText} 판정 — null · {@code strip()} 뒤 빈 값을 거절한다. */
public class DisplayRequiredTextValidator implements ConstraintValidator<DisplayRequiredText, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && !value.strip().isEmpty();
    }
}
