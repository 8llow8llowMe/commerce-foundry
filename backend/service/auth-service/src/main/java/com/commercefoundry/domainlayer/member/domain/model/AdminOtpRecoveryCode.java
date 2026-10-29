package com.commercefoundry.domainlayer.member.domain.model;

/**
 * 아직 쓰지 않은 관리자 OTP 복구 코드 한 개 (#238). 원문은 저장하지 않고 정규화한 값({@link RecoveryCode#normalize})의 BCrypt 해시만 둔다.
 *
 * @param id       복구 코드 아이디 (Snowflake) — 사용 표시(조건부 UPDATE)의 대상
 * @param memberId 관리자 회원 아이디
 * @param codeHash BCrypt 해시
 */
public record AdminOtpRecoveryCode(long id, long memberId, String codeHash) {

    /** 해시는 가린다 — 오프라인 대입의 입력이 된다. */
    @Override
    public String toString() {
        return "AdminOtpRecoveryCode[id=" + id + ", memberId=" + memberId + ", codeHash=****]";
    }
}
