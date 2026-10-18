package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AuthTokenInfo;
import com.commercefoundry.domainlayer.member.application.port.out.AccessTokenRevocationPort;
import com.commercefoundry.domainlayer.member.application.port.out.RefreshTokenStorePort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenFormat;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import com.commercefoundry.global.properties.AuthTokenProperties;
import com.commercefoundry.security.issuer.IssuedAccessToken;
import com.commercefoundry.security.issuer.JwtIssuanceProperties;
import com.commercefoundry.security.issuer.JwtIssuer;
import com.commercefoundry.security.issuer.OpaqueTokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * access · refresh 토큰 발급, refresh 회전 · 재사용 감지, 로그아웃 ({@code security-guide.md} §2-3 · §7-1).
 *
 * <p>DB 를 쓰지 않는다 — 저장소는 Redis({@link RefreshTokenStorePort} · {@link AccessTokenRevocationPort})뿐이라 트랜잭션을 걸지 않는다.
 * 로그에는 토큰 원문을 남기지 않는다 — refresh 는 지문 앞 몇 글자, access 는 jti · memberId 만.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthTokenProcessor {

    private final RefreshTokenStorePort refreshTokenStorePort;
    private final AccessTokenRevocationPort accessTokenRevocationPort;
    private final JwtIssuer jwtIssuer;
    private final OpaqueTokenGenerator opaqueTokenGenerator;
    private final JwtIssuanceProperties jwtIssuanceProperties;
    private final AuthTokenProperties authTokenProperties;
    private final Clock clock;

    /** 로그인 — 새 family 로 refresh 토큰을 저장하고 access 토큰을 발급한다. */
    public AuthTokenInfo issueForLogin(Member member) {
        String refreshToken = opaqueTokenGenerator.generate();
        refreshTokenStorePort.saveNewFamily(opaqueTokenGenerator.fingerprint(refreshToken), member.id(), UUID.randomUUID().toString(), refreshTokenTtl());
        return issue(member, refreshToken);
    }

    /**
     * 재발급의 첫 단계 — 모양을 보고(지문 계산 전) 저장소에서 세션을 읽는다. 회전은 하지 않는다. 빈 값 · null 도 모양 오류다.
     *
     * @throws MemberException {@link MemberErrorCode#REFRESH_TOKEN_MALFORMED} — 발급 모양이 아니다.
     *                         {@link MemberErrorCode#REFRESH_TOKEN_INVALID} — 토큰 또는 family 가 없다
     */
    public RefreshTokenSession findSession(String refreshToken) {
        if (!RefreshTokenFormat.isWellFormed(refreshToken)) {
            throw new MemberException(MemberErrorCode.REFRESH_TOKEN_MALFORMED);
        }
        return refreshTokenStorePort.findSession(opaqueTokenGenerator.fingerprint(refreshToken))
            .orElseThrow(() -> new MemberException(MemberErrorCode.REFRESH_TOKEN_INVALID));
    }

    /**
     * 세션을 회전하고 새 쌍을 발급한다. 회원 조회는 회전 <b>전에</b> 끝나 있다 — DB 장애로 "회전됐는데 새 토큰을 못 받은" 상태가 생기지 않게.
     *
     * @param customer 세션 주인 — 지금도 고객 로그인을 쓸 수 있는 회원({@code MemberLoginProcessor#findCustomer}). 없거나 관리자면 null 이고,
     *                 그때는 family 를 폐기하고 무효로 답한다(고객 로그인은 관리자를 막으므로 정상 흐름에는 없다)
     * @throws MemberException {@link MemberErrorCode#REFRESH_TOKEN_INVALID} — 주인 없음 · 관리자 · 읽은 뒤 무효가 됐다.
     *                         {@link MemberErrorCode#REFRESH_TOKEN_REUSED} — 유예 밖 재사용 · 형제 상한 초과, family 를 폐기했다
     */
    public AuthTokenInfo reissue(RefreshTokenSession session, Member customer) {
        if (customer == null) {
            refreshTokenStorePort.revokeFamily(session.familyId());
            log.info("refresh token owner is missing or not a customer, family revoked: memberId={}, familyId={}", session.memberId(), session.familyId());
            throw new MemberException(MemberErrorCode.REFRESH_TOKEN_INVALID);
        }

        String refreshToken = opaqueTokenGenerator.generate();
        RefreshTokenRotationPolicy policy = new RefreshTokenRotationPolicy(
            authTokenProperties.refreshReuseGrace(), authTokenProperties.refreshReuseGraceMaxSiblings(), refreshTokenTtl()
        );
        RefreshTokenRotation rotation = refreshTokenStorePort.rotate(session, opaqueTokenGenerator.fingerprint(refreshToken), clock.instant(), policy);
        return switch (rotation) {
            case ROTATED -> issue(customer, refreshToken);
            case REISSUED_IN_GRACE -> {
                log.info("refresh token reused in grace, sibling issued: memberId={}, familyId={}, fingerprint={}",
                    session.memberId(), session.familyId(), session.fingerprintForLog());
                yield issue(customer, refreshToken);
            }
            case INVALID -> throw new MemberException(MemberErrorCode.REFRESH_TOKEN_INVALID);
            case REUSE_DETECTED -> {
                log.warn("refresh token reuse detected, family revoked: memberId={}, familyId={}, fingerprint={}",
                    session.memberId(), session.familyId(), session.fingerprintForLog());
                throw new MemberException(MemberErrorCode.REFRESH_TOKEN_REUSED);
            }
        };
    }

    /**
     * 로그아웃 — ① refresh family 폐기 → ② access 토큰 차단 목록 등록 순서다. ② 가 실패해도 남는 것은 수명이 짧은 access 토큰(≤61분)뿐이다
     * (반대 순서면 14일짜리 refresh 가 산다). 사용자 확정 결정 7 은 반대 순서(차단 → family)였다 — PR 에서 확인을 받는다.
     * 되돌릴 때는 아래 두 줄의 순서만 바꾼다 ({@code security-guide.md} §2-3 로그아웃).
     * 어느 쪽이든 저장소 장애면 503 이고, 다시 부르면 같은 결과가 된다(멱등).
     *
     * @param tokenId 로그아웃하는 access 토큰의 {@code jti}
     */
    public void logout(long memberId, String tokenId, String refreshToken) {
        revokeOwnFamily(memberId, refreshToken);
        accessTokenRevocationPort.revoke(tokenId);
    }

    /**
     * refresh 토큰이 모양이 맞고 · 저장소에 있고 · 이 회원의 것일 때만 그 family 를 지운다. 없는 값 · 모르는 토큰 · 남의 토큰은 건드리지 않는다 —
     * 남의 refresh 토큰을 손에 넣은 사람이 자기 로그아웃으로 남의 세션을 끊지 못하게.
     */
    private void revokeOwnFamily(long memberId, String refreshToken) {
        if (!RefreshTokenFormat.isWellFormed(refreshToken)) {
            return;
        }
        refreshTokenStorePort.findSession(opaqueTokenGenerator.fingerprint(refreshToken))
            .filter(session -> session.memberId() == memberId)
            .ifPresent(session -> refreshTokenStorePort.revokeFamily(session.familyId()));
    }

    private AuthTokenInfo issue(Member member, String refreshToken) {
        IssuedAccessToken accessToken = jwtIssuer.issueAccessToken(member.id(), member.role());
        long accessTokenExpiresIn = Math.max(0, Duration.between(clock.instant(), accessToken.expiresAt()).toSeconds());
        return new AuthTokenInfo(accessToken.tokenValue(), accessTokenExpiresIn, refreshToken, refreshTokenTtl().toSeconds());
    }

    private Duration refreshTokenTtl() {
        return jwtIssuanceProperties.refreshTokenTtl();
    }
}
