package com.commercefoundry.global.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 배송지 주소록 설정.
 *
 * @param maxPerMember 회원당 저장할 수 있는 배송지 수. 넘으면 409(AUTH_021)다. 목록은 페이지 없이 전체를 내므로 이 값이 응답 크기의 상한이다 —
 *     그래서 100 을 넘기지 못하게 한다. 범위를 벗어나면 기동하지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "auth.shipping-address")
public record ShippingAddressProperties(@Min(1) @Max(100) @DefaultValue("20") int maxPerMember) {
}
