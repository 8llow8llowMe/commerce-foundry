package com.commercefoundry.domainlayer.member.adapter.out.redis;

import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 정규화한 이메일을 Redis 키에 넣을 값으로 바꾼다 — HMAC-SHA256 의 앞 16바이트를 base64url(패딩 없음, 22자)로 쓴다 (#66).
 * 형식은 게이트웨이 {@code ClientIpHasher} 와 같다. 키 없는 해시는 흔한 이메일 목록을 대입하면 되돌릴 수 있어 쓰지 않는다.
 * 같은 키를 쓰는 auth 인스턴스끼리 같은 이메일은 같은 값이 된다. 받은 값을 그대로 해시한다 — 정규화({@code Member.canonicalEmail})는
 * {@link LoginAttemptRedisAdapter} 가 키를 만들 때 한 곳에서 한다.
 *
 * <p>{@code auth.login-guard.enabled} 가 꺼져 있으면 키 없이 뜨고, {@link #hash} 를 부르면 {@link IllegalStateException} 이다 —
 * 꺼진 상태에서는 부르지 않는다.
 */
@Component
public class LoginAttemptKeyHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int KEPT_BYTES = 16;

    /** 꺼져 있으면 {@code null}. */
    private final SecretKeySpec key;

    public LoginAttemptKeyHasher(AuthLoginGuardProperties properties) {
        if (!properties.enabled()) {
            this.key = null;
            return;
        }
        this.key = new SecretKeySpec(properties.hashKeyBytes(), ALGORITHM);
        // 기동 때 한 번 만들어 본다 — 알고리즘 · 키 문제를 첫 요청이 아니라 기동에서 드러낸다
        newMac();
    }

    /** @param normalizedEmail 정규화한 이메일. UTF-8 바이트로 해시한다 */
    public String hash(String normalizedEmail) {
        if (key == null) {
            // 키 · 이메일은 메시지에 넣지 않는다
            throw new IllegalStateException("login attempt key hashing is disabled (auth.login-guard.enabled=false)");
        }
        byte[] digest = newMac().doFinal(normalizedEmail.getBytes(StandardCharsets.UTF_8));
        byte[] kept = new byte[KEPT_BYTES];
        System.arraycopy(digest, 0, kept, 0, KEPT_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(kept);
    }

    /** {@link Mac} 은 스레드 안전하지 않아 호출마다 만든다. */
    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("login attempt hmac is not available", failure);
        }
    }
}
