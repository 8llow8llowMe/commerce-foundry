package com.commercefoundry.domainlayer.member.domain.model;

/**
 * 회원 동의 유형. 동의 이력({@code member_agreement})의 유형 칸이다.
 *
 * <p>값을 더하거나 빼면 {@code member_agreement.agreement_type} 의 check 제약도 바꾼다 ({@code coding-conventions.md} §9-8).
 */
public enum AgreementType {

    /** 이용약관 — 가입 필수. */
    TERMS_OF_SERVICE(true),
    /** 개인정보 수집 · 이용 — 가입 필수. */
    PRIVACY_POLICY(true),
    /** 마케팅 정보 수신 — 선택. 내 정보에서 바꿀 수 있다. */
    MARKETING(false);

    private final boolean required;

    AgreementType(boolean required) {
        this.required = required;
    }

    /** 가입하려면 반드시 동의해야 하는가. */
    public boolean required() {
        return required;
    }
}
