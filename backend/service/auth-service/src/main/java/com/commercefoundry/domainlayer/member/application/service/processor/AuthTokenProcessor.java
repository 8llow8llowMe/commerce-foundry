package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminAccessTokenInfo;
import com.commercefoundry.domainlayer.member.application.info.AuthTokenInfo;
import com.commercefoundry.domainlayer.member.application.port.out.AccessTokenRevocationPort;
import com.commercefoundry.domainlayer.member.application.port.out.RefreshTokenStorePort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenFormat;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationResult;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import com.commercefoundry.security.issuer.IssuedAccessToken;
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
    /** 유예 · 형제 상한 · refresh TTL · family 절대 수명. 두 설정의 어긋남은 빈을 만들 때(기동) 거른다 ({@code AuthTokenBeansConfig}). */
    private final RefreshTokenRotationPolicy rotationPolicy;
    private final Clock clock;

    /**
     * 로그인 — 새 family 로 refresh 토큰을 저장하고 access 토큰을 발급한다. family 에는 지금을 로그인 시각으로 남긴다(절대 수명의 기준).
     * refresh 수명은 정책 TTL 그대로다 — 절대 수명 ≥ TTL 이라 로그인 시점에는 줄어들 일이 없다.
     */
    public AuthTokenInfo issueForLogin(Member member) {
        String refreshToken = opaqueTokenGenerator.generate();
        refreshTokenStorePort.saveNewFamily(
            opaqueTokenGenerator.fingerprint(refreshToken), member.id(), UUID.randomUUID().toString(), clock.instant(), rotationPolicy.ttl()
        );
        return issue(member, refreshToken, rotationPolicy.ttl());
    }

    /**
     * 관리자 로그인(#6) — access 토큰만 발급한다. refresh 토큰을 만들지 않고 저장소({@link RefreshTokenStorePort})도 부르지 않는다.
     * 관리자 세션은 access 수명까지다 — refresh family 를 만들어도 재발급이 고객만 받아({@code MemberLoginProcessor#findCustomer}) 쓰지 못할 키만 남는다.
     * 로그아웃은 고객과 같은 {@link #logout}(refresh 없이 access 차단만)이다.
     *
     * @throws IllegalArgumentException 관리자 계정이 아니다 — 관리자 인증({@code MemberLoginProcessor#authenticateAdmin})을 지난 회원만 온다
     */
    public AdminAccessTokenInfo issueAdminAccessToken(Member admin) {
        if (!admin.isAdmin()) {
            throw new IllegalArgumentException("관리자 계정만 관리자 access 토큰을 받는다");
        }
        IssuedAccessToken accessToken = jwtIssuer.issueAccessToken(admin.id(), admin.role());
        return new AdminAccessTokenInfo(accessToken.tokenValue(), Math.max(0, Duration.between(clock.instant(), accessToken.expiresAt()).toSeconds()));
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
     * @throws MemberException {@link MemberErrorCode#REFRESH_TOKEN_INVALID} — 주인 없음 · 관리자 · 읽은 뒤 무효가 됐다 · family 절대 수명 초과 · family 값 손상
     *                         (뒤의 둘은 family 를 폐기했다).
     *                         {@link MemberErrorCode#REFRESH_TOKEN_REUSED} — 유예 밖 재사용 · 형제 상한 초과, family 를 폐기했다
     */
    public AuthTokenInfo reissue(RefreshTokenSession session, Member customer) {
        if (customer == null) {
            refreshTokenStorePort.revokeFamily(session.familyId());
            log.info("refresh token owner is missing or not a customer, family revoked: memberId={}, familyId={}", session.memberId(), session.familyId());
            throw new MemberException(MemberErrorCode.REFRESH_TOKEN_INVALID);
        }

        String refreshToken = opaqueTokenGenerator.generate();
        RefreshTokenRotationResult result = refreshTokenStorePort.rotate(session, opaqueTokenGenerator.fingerprint(refreshToken), clock.instant(), rotationPolicy);
        return switch (result.rotation()) {
            case ROTATED -> issue(customer, refreshToken, result.refreshTokenTtl());
            case REISSUED_IN_GRACE -> {
                log.info("refresh token reused in grace, sibling issued: memberId={}, familyId={}, fingerprint={}",
                    session.memberId(), session.familyId(), session.fingerprintForLog());
                yield issue(customer, refreshToken, result.refreshTokenTtl());
            }
            case INVALID -> throw new MemberException(MemberErrorCode.REFRESH_TOKEN_INVALID);
            // 정상 만료라 재사용(AUTH_012 · warn)과 나눈다. 클라이언트에는 만료 · 폐기와 같은 AUTH_011 — BFF 는 4xx 로 쿠키를 지우고 로그인 화면으로 보낸다
            case FAMILY_EXPIRED -> {
                log.info("refresh token family reached its max lifetime, family revoked: memberId={}, familyId={}", session.memberId(), session.familyId());
                throw new MemberException(MemberErrorCode.REFRESH_TOKEN_INVALID);
            }
            // 정상 흐름에는 없다 — 저장소를 직접 고쳤거나 시계가 허용 오차보다 앞선 인스턴스가 로그인을 받았다. 받아 주면 절대 수명 상한이 사라지므로 warn 으로 드러낸다.
            // 사용자에게는 만료와 같은 일이라 응답은 AUTH_011 이다. 손상은 family 의 일이라 토큰 지문은 남기지 않는다
            case FAMILY_CORRUPTED -> {
                log.warn("refresh token family value is corrupted, family revoked: memberId={}, familyId={}", session.memberId(), session.familyId());
                throw new MemberException(MemberErrorCode.REFRESH_TOKEN_INVALID);
            }
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

    /**
     * @param refreshTokenTtl 저장소가 새 refresh 토큰에 실제로 건 TTL — 응답 {@code refreshTokenExpiresIn}(초, 버림)이 BFF 쿠키 만료가 된다.
     *                        버림이라 쿠키가 서버 키보다 먼저(1초 안) 사라진다 — 반대면 이미 지운 토큰으로 재발급을 부른다
     */
    private AuthTokenInfo issue(Member member, String refreshToken, Duration refreshTokenTtl) {
        IssuedAccessToken accessToken = jwtIssuer.issueAccessToken(member.id(), member.role());
        long accessTokenExpiresIn = Math.max(0, Duration.between(clock.instant(), accessToken.expiresAt()).toSeconds());
        return new AuthTokenInfo(accessToken.tokenValue(), accessTokenExpiresIn, refreshToken, refreshTokenTtl.toSeconds());
    }
}
