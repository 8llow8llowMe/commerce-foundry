package com.commercefoundry.domainlayer.member.domain.model;

import java.util.Objects;

/**
 * 동의 이력 한 줄. 추가만 하고 고치거나 지우지 않는다 — 언제 어느 약관 버전에 동의 · 철회했는지가 기록이다.
 * 현재 상태는 그 회원 · 유형의 가장 최근 행이다.
 *
 * @param agreed 동의했으면 true, 동의하지 않았거나 철회했으면 false
 * @param termsVersion 그때 시행 중이던 약관 버전 ({@code auth.agreement.versions.*}). 클라이언트가 보내지 않는다
 */
public record MemberAgreement(long id, long memberId, AgreementType agreementType, boolean agreed, String termsVersion) {

    public MemberAgreement {
        Objects.requireNonNull(agreementType, "agreementType");
        if (termsVersion == null || termsVersion.isBlank()) {
            throw new IllegalArgumentException("termsVersion 이 비었다");
        }
    }
}
