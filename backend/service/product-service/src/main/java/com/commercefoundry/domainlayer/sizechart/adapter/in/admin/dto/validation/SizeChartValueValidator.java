package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.validation;

import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartValidationMessage;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartLimits;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;

/** {@link SizeChartValue} 판정. DB check {@code cell_value > 0 and cell_value <= 9999.99} 와 같은 범위다. */
public class SizeChartValueValidator implements ConstraintValidator<SizeChartValue, BigDecimal> {

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        if (value != null && value.scale() <= SizeChartLimits.MEASUREMENT_SCALE && value.compareTo(BigDecimal.ZERO) > 0
            && value.compareTo(SizeChartLimits.MAX_MEASUREMENT) <= 0) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(SizeChartValidationMessage.CELL_VALUE_RANGE).addConstraintViolation();
        return false;
    }
}
