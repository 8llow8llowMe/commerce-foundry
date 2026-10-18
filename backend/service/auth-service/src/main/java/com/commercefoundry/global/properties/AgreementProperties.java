package com.commercefoundry.global.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 시행 중인 약관 버전 — 동의 이력({@code member_agreement.terms_version})에 그대로 남는다. 클라이언트가 보내지 않는다.
 *
 * <p>약관을 고치면 시행일 등으로 올린다. 값이 비거나 20자({@code terms_version} 길이)를 넘으면 <b>기동하지 않는다</b> —
 * 어느 약관에 동의했는지 모르는 이력이 쌓이지 않게.
 *
 * @param versions 유형별 버전
 */
@Validated
@ConfigurationProperties(prefix = "auth.agreement")
public record AgreementProperties(@NotNull @Valid Versions versions) {

    /** {@code member_agreement.terms_version} 길이. */
    public static final int VERSION_MAX_LENGTH = 20;

    /**
     * @param termsOfService 이용약관
     * @param privacyPolicy 개인정보 수집 · 이용
     * @param marketing 마케팅 정보 수신
     */
    public record Versions(
        @NotBlank @Size(max = VERSION_MAX_LENGTH) String termsOfService,
        @NotBlank @Size(max = VERSION_MAX_LENGTH) String privacyPolicy,
        @NotBlank @Size(max = VERSION_MAX_LENGTH) String marketing
    ) {
    }
}
