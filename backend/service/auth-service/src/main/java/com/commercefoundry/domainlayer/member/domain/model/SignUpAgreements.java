package com.commercefoundry.domainlayer.member.domain.model;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 가입 시 받은 동의 3종. 필수 동의(이용약관 · 개인정보)가 빠지면 만들어지지 않는다 — 요청 검증(AUTH_111 · AUTH_112)을 지나온 값이라
 * 여기서 걸리면 호출 코드의 오류다.
 */
public record SignUpAgreements(boolean termsOfService, boolean privacyPolicy, boolean marketing) {

    public SignUpAgreements {
        if (!termsOfService || !privacyPolicy) {
            throw new IllegalArgumentException("필수 동의(이용약관 · 개인정보)가 없다");
        }
    }

    public boolean agreed(AgreementType type) {
        return switch (type) {
            case TERMS_OF_SERVICE -> termsOfService;
            case PRIVACY_POLICY -> privacyPolicy;
            case MARKETING -> marketing;
        };
    }

    /**
     * 동의 3종을 모두 이력 행으로 만든다 — 마케팅을 거절했어도 "거절" 한 줄을 남긴다. 순서는 {@link AgreementType} 선언 순서다.
     *
     * @param idSupplier 행 아이디 (Snowflake)
     * @param termsVersionOf 유형별 시행 중인 약관 버전
     */
    public List<MemberAgreement> toHistory(long memberId, LongSupplier idSupplier, Function<AgreementType, String> termsVersionOf) {
        return Arrays.stream(AgreementType.values())
            .map(type -> new MemberAgreement(idSupplier.getAsLong(), memberId, type, agreed(type), termsVersionOf.apply(type)))
            .toList();
    }
}
