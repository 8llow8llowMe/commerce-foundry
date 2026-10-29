package com.commercefoundry.domainlayer.skucost.adapter.in.admin.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SKU 원가 변경 사유 — 필수(유니코드 공백 · 서식 문자만이면 빈 값), 제어 · 서식 문자 금지, 앞뒤 공백을 뺀 뒤 200자 이하.
 * 판정은 {@link SkuCostReasonValidator}. 위반은 {@code reason} 필드로 낸다 (선례: 카테고리 이름 {@code ValidCategoryName}).
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SkuCostReasonValidator.class)
public @interface ValidSkuCostReason {

    /** 쓰지 않는다 — 검증기가 위반마다 {@code SkuCostValidationMessage} 의 메시지로 바꿔 낸다. */
    String message() default "PRODUCT_100:요청 값이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
