package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link LookRequiredText} 판정 — null · {@code strip()} 뒤 빈 값을 거절한다. */
public class LookRequiredTextValidator implements ConstraintValidator<LookRequiredText, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value != null && !value.strip().isEmpty();
    }
}
