package com.commercefoundry.security.issuer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * refresh 토큰처럼 서버가 저장해 두고 대조하는 불투명 토큰을 만든다. JWT 가 아니라 뜻 없는 난수다 —
 * 서버 저장소에 없으면 무효이므로 서명·claim 이 필요 없고, 탈취돼도 내용이 드러나지 않는다.
 *
 * <p>저장소에는 원문이 아니라 {@link #fingerprint(String)} 를 키로 둔다. 저장소가 새도 토큰으로 쓸 수 없다.
 * 저장·회전·재사용 감지는 auth-service(#4)가 한다.
 */
public final class OpaqueTokenGenerator {

    /** 256비트 엔트로피. base64url 무패딩으로 43자다. */
    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** SHA-256 을 base64url 무패딩(43자)으로. 같은 입력이면 항상 같다 — Redis 키({@code RedisKey} id)로 그대로 쓸 수 있다. */
    public String fingerprint(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 은 비어 있을 수 없습니다");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", impossible);
        }
    }
}
