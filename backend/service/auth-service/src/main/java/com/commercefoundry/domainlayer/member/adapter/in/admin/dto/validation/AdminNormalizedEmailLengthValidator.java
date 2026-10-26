package com.commercefoundry.domainlayer.member.adapter.in.admin.dto.validation;

import com.commercefoundry.domainlayer.member.domain.model.Member;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link AdminNormalizedEmailLength} 판정. 원문 길이 · 필수는 {@code @Size} · {@code @NotBlank} 몫이다. */
public class AdminNormalizedEmailLengthValidator implements ConstraintValidator<AdminNormalizedEmailLength, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank() || value.length() > Member.EMAIL_MAX_LENGTH) {
            return true;
        }
        return Member.canonicalEmail(value).length() <= Member.EMAIL_MAX_LENGTH;
    }
}
