package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.info.AdminLoginChallengeInfo;
import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpChallengeStorePort;
import com.commercefoundry.global.properties.AdminOtpProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 로그인 2단계 챌린지 (#238 PR-B). 소셜 state({@code SocialAuthorizationProcessor})와 같은 모양이다 — 32바이트 {@link SecureRandom} 을 base64url(43자)로 내주고,
 * 저장소에는 그 SHA-256(base64url 43자)만 둔다. 챌린지는 비밀번호를 확인한 관리자 한 명과 발급 시각에 묶이고 TTL({@code auth.admin-otp.challenge-ttl}, 기본 5분) 동안만 산다.
 *
 * <ul>
 *   <li>{@link #find} — 소비하지 않고 읽는다. 코드가 틀려도 TTL 안에서는 같은 챌린지로 다시 시도할 수 있다(오타마다 비밀번호를 다시 받지 않는다) —
 *       대입은 2단계 시도 제한({@code ADMIN_OTP_LOGIN})이 막는다</li>
 *   <li>{@link #consume} — 코드 확인이 끝난 뒤, 코드를 소비하기 전에 원자적으로 지운다(GETDEL). 동시 2단계 중 진 쪽은 {@code AUTH_034} 다</li>
 * </ul>
 * 모양이 다른 값(길이 · 글자)은 저장소를 부르지 않고 {@code AUTH_034} 다. 원문 · 해시는 예외 · 로그에 싣지 않는다. 저장소 장애는 {@code AUTH_013}(503) 그대로다.
 */
@Component
@RequiredArgsConstructor
public class AdminLoginChallengeProcessor {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int ISSUE_ATTEMPTS = 3;

    private final AdminOtpChallengeStorePort adminOtpChallengeStorePort;
    private final AdminOtpProperties adminOtpProperties;

    /**
     * 새 챌린지를 둔다. 해시가 겹치는 일(2^-256)이 세 번 이어지면 저장소 이상으로 보고 {@code AUTH_013} 이다.
     *
     * @param issuedAt 발급 시각 — 2단계가 관리자 컷오프(비밀번호 변경 · 비활성화)와 비교한다
     */
    public AdminLoginChallengeInfo issue(long adminMemberId, Instant issuedAt) {
        AdminOtpChallenge challenge = new AdminOtpChallenge(adminMemberId, issuedAt);
        for (int attempt = 0; attempt < ISSUE_ATTEMPTS; attempt++) {
            String token = randomToken();
            if (adminOtpChallengeStorePort.save(hash(token), challenge, adminOtpProperties.challengeTtl())) {
                return new AdminLoginChallengeInfo(token, adminOtpProperties.challengeTtl().toSeconds());
            }
        }
        throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    /** 챌린지(관리자 · 발급 시각). 없음 · 만료 · 모양 오류는 모두 {@code AUTH_034} 다. */
    public AdminOtpChallenge find(String challengeToken) {
        if (!tokenShape(challengeToken)) {
            throw invalid();
        }
        return adminOtpChallengeStorePort.find(hash(challengeToken)).orElseThrow(AdminLoginChallengeProcessor::invalid);
    }

    /**
     * 챌린지를 지운다 — 이 요청이 지웠고 {@link #find} 로 읽은 것과 같은 챌린지(관리자 · 발급 시각)였을 때만 통과한다.
     * 아니면(동시 요청이 먼저 씀 · 그 사이 만료) {@code AUTH_034}.
     */
    public void consume(String challengeToken, AdminOtpChallenge expected) {
        if (!tokenShape(challengeToken)) {
            throw invalid();
        }
        Optional<AdminOtpChallenge> consumed = adminOtpChallengeStorePort.consume(hash(challengeToken));
        if (consumed.isEmpty() || !consumed.get().equals(expected)) {
            throw invalid();
        }
    }

    private static MemberException invalid() {
        return new MemberException(MemberErrorCode.OTP_CHALLENGE_INVALID);
    }

    private static boolean tokenShape(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
