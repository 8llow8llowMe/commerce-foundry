package com.commercefoundry.domainlayer.member.application.info;

/**
 * 내 정보.
 *
 * @param phone 숫자만. 없으면 null
 * @param marketingAgreed 마케팅 동의 이력의 가장 최근 행. 이력이 없으면 false
 */
public record MemberProfileInfo(long memberId, String email, String name, String phone, boolean marketingAgreed) {

    /** 개인정보(이메일 · 이름 · 전화)를 싣지 않는다. */
    @Override
    public String toString() {
        return "MemberProfileInfo[memberId=" + memberId + ", marketingAgreed=" + marketingAgreed + "]";
    }
}
