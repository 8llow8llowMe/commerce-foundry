package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 사이즈표 문구. 카테고리 이름과 같은 규칙(앞뒤 {@code strip()}, 제어 · 서식 문자 금지, 코드 포인트 길이)을 이 컨텍스트 안에서 판정한다.
 * 한 값에 위반 하나만 낸다.
 */
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SizeChartTextValidator.class)
public @interface SizeChartText {

    String message() default "PRODUCT_100:요청 값이 올바르지 않습니다.";

    boolean required() default false;

    String requiredMessage() default "";

    String invalidCharacterMessage();

    String tooLongMessage();

    int maxCodePoints();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
