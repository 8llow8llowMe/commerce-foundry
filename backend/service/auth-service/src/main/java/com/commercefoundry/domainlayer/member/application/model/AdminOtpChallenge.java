package com.commercefoundry.domainlayer.member.application.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 저장소에 둔 관리자 로그인 2단계 챌린지 한 건 (#238 PR-B) — 비밀번호를 확인한 관리자와 그 발급 시각이다. 챌린지 원문 · 해시는 담지 않는다.
 *
 * <p>발급 시각은 비밀번호 변경 · 비활성화 컷오프({@code auth:admin-access-cutoff:{memberId}})와 비교한다 — 컷오프 이전에 받은 챌린지로는 2단계를 통과하지 못한다
 * ({@code security-guide.md} §11-1).
 *
 * @param memberId 챌린지를 받은 관리자
 * @param issuedAt 챌린지 발급 시각(밀리초까지 저장한다)
 */
public record AdminOtpChallenge(long memberId, Instant issuedAt) {

    public AdminOtpChallenge {
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId 는 양수여야 한다");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
    }
}
