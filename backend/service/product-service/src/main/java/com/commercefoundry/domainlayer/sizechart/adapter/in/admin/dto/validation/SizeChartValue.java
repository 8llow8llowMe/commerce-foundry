package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.validation;

import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartValidationMessage;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 치수 값. null · 0 이하 · 9999.99 초과 · 소수 셋째 자리 이상은 {@code PRODUCT_197} 이다. */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SizeChartValueValidator.class)
public @interface SizeChartValue {

    String message() default SizeChartValidationMessage.CELL_VALUE_RANGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
