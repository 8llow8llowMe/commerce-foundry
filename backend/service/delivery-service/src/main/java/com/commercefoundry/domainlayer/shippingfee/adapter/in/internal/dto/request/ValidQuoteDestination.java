package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 견적 요청의 국가별 필드 규칙 — KR 이면 우편번호(숫자 5자리) 필수, 그 밖의 국가면 무게(1g 이상) 필수 · 우편번호 선택.
 * 판정은 {@link QuoteDestinationValidator}. 위반은 필드 이름({@code postalCode} · {@code weightGrams})으로 낸다.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = QuoteDestinationValidator.class)
public @interface ValidQuoteDestination {

    /** 쓰지 않는다 — 검증기가 필드별 메시지({@code DeliveryValidationMessage})로 바꿔 낸다. */
    String message() default "DELIVERY_100:요청 값이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
