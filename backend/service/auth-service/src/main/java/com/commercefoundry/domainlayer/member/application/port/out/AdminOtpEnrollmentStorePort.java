package com.commercefoundry.domainlayer.member.application.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * 확인 전 관리자 OTP 등록 (#238 PR-A2) — 관리자당 하나, TTL 이 지나면 사라진다. DB 에는 확인된 자격만 둔다.
 * 비밀은 원문으로 주고받고, 암호화(AAD = 회원 단위 등록 용도)와 저장 형식은 구현이 정한다 — 저장소에는 암호문만 남는다.
 *
 * <p>저장소에 닿지 못하면 {@code AUTH_013}(503), 저장된 값을 복호화하지 못하면(키 없음 · 손상 · 다른 회원의 암호문) {@code AUTH_039}(503)다.
 */
public interface AdminOtpEnrollmentStorePort {

    /** 진행 중인 등록을 이 비밀로 바꾼다 — 같은 관리자가 등록을 다시 시작하면 이전 비밀은 버려진다. */
    void save(long memberId, byte[] secret, Duration ttl);

    /** 진행 중인 등록의 비밀. 없거나 만료됐으면 비어 있다. */
    Optional<byte[]> find(long memberId);

    /** 진행 중인 등록을 지운다. 없으면 아무 일도 없다. */
    void delete(long memberId);
}
