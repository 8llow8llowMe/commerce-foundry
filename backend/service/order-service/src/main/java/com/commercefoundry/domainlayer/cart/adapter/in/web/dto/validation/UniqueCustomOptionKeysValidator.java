package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.validation;

import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.request.CustomOptionRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * fieldKey 는 형식 제약(영문 · 숫자 · 밑줄)이 있어 정규화 없이 그대로 비교한다. null 원소 · null fieldKey 는 다른 제약이 본다.
 */
public class UniqueCustomOptionKeysValidator implements ConstraintValidator<UniqueCustomOptionKeys, List<CustomOptionRequest>> {

    @Override
    public boolean isValid(List<CustomOptionRequest> customOptions, ConstraintValidatorContext context) {
        if (customOptions == null) {
            return true;
        }
        Set<String> fieldKeys = new HashSet<>();
        for (CustomOptionRequest option : customOptions) {
            if (option != null && option.fieldKey() != null && !fieldKeys.add(option.fieldKey())) {
                return false;
            }
        }
        return true;
    }
}
