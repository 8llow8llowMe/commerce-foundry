package com.commercefoundry.domainlayer.member.application.command;

/**
 * 내 정보 부분 수정. null 인 칸은 바꾸지 않는다.
 *
 * @param phone 빈 문자열이면 번호를 지운다
 * @param marketingAgreed 마케팅 정보 수신 동의. 현재 값과 다를 때만 동의 이력을 한 줄 남긴다
 */
public record MemberProfileUpdateCommand(String name, String phone, Boolean marketingAgreed) {

    /** 개인정보(이름 · 전화)를 싣지 않는다. */
    @Override
    public String toString() {
        return "MemberProfileUpdateCommand[nameChanged=" + (name != null) + ", phoneChanged=" + (phone != null) + ", marketingAgreed=" + marketingAgreed + "]";
    }
}
