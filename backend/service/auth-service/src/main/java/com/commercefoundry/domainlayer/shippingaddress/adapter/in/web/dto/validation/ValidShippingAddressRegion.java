package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 배송지의 국가별 필드 규칙 — 연락처 형식, 우편번호(KR 필수 · 숫자 5자리 / 해외 선택), 도시(해외 필수).
 * 판정은 {@link ShippingAddressRegionValidator}. 위반은 필드 이름({@code recipientPhone} · {@code postalCode} · {@code city})으로 낸다.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ShippingAddressRegionValidator.class)
public @interface ValidShippingAddressRegion {

    /** 쓰지 않는다 — 검증기가 필드별 메시지({@code ShippingAddressValidationMessage})로 바꿔 낸다. */
    String message() default "AUTH_100:요청 값이 올바르지 않습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
