package com.commercefoundry.domainlayer.member.adapter.out.crypto;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.global.properties.AdminOtpProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 관리자 OTP 비밀의 저장 형식 (#238) — AES-256-GCM. DB · Redis 가 새도 비밀(곧 2단계 인증)이 같이 새지 않게 한다.
 * 영속성 어댑터(확정 자격)와 Redis 어댑터(미확정 등록, PR-A2)가 함께 쓰므로 어느 한쪽 패키지가 아니라 {@code adapter/out/crypto} 에 둔다.
 *
 * <ul>
 *   <li>IV 는 암호화마다 새 12바이트 난수, 태그 128비트. 저장값은 base64(IV ‖ 암호문 ‖ 태그)</li>
 *   <li>AAD 는 용도와 주인을 묶는다 — 확정 자격 {@code "admin-otp:v1:credential:{memberId}:{credentialId}"},
 *       미확정 등록 {@code "admin-otp:v1:enrollment:{memberId}"}. 다른 관리자 행 · 같은 관리자의 옛 자격 행(해제 전 백업 등) · 다른 용도로
 *       암호문을 옮겨 붙이면 복호화가 실패한다</li>
 *   <li>{@code key_id} 는 base64url(SHA-256(키)[0:8]) 11자. 복호화할 키를 고르는 데만 쓴다 — 현재 키 또는 이전 키({@code AUTH_OTP_SECRET_KEY_PREVIOUS}).
 *       둘 다 아니거나 태그가 맞지 않으면 {@code AUTH_039}(503)다. 원인 예외 · 메시지에 키 · 암호문을 싣지 않는다</li>
 * </ul>
 *
 * <p>회전: 이전 키로 읽은 확정 자격은 다음 사용 기록(조건부 UPDATE) 때 현재 키로 다시 암호화한다 — 판정은 영속성 어댑터가 {@link #isCurrent} 로 한다.
 */
@Component
public class AdminOtpSecretCipher {

    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    static final String AAD_PREFIX = "admin-otp:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_ID_BYTES = 8;

    private final Key current;
    private final Optional<Key> previous;
    private final SecureRandom random = new SecureRandom();

    public AdminOtpSecretCipher(AdminOtpProperties properties) {
        this.current = Key.of(properties.secretKeyBytes());
        this.previous = properties.previousSecretKeyBytes().map(Key::of);
        // 기동 때 한 번 돌려 본다 — 알고리즘 · 키 문제를 첫 로그인이 아니라 기동에서 드러낸다
        Sealed probe = sealEnrollment(0L, new byte[1]);
        openEnrollment(0L, probe.ciphertext(), probe.keyId());
    }

    /** 확정 자격의 비밀을 현재 키로 암호화한다. 자격 아이디가 AAD 에 들어가므로 저장 전에 아이디가 정해져 있어야 한다. */
    public Sealed sealCredential(long memberId, long credentialId, byte[] secret) {
        return encrypt(credentialAad(memberId, credentialId), secret);
    }

    /** 확정 자격의 암호문을 {@code keyId} 에 맞는 키로 복호화한다. 맞는 키가 없거나 · 형식이 깨졌거나 · 태그(AAD 포함)가 맞지 않으면 {@code AUTH_039}. */
    public byte[] openCredential(long memberId, long credentialId, String ciphertext, String keyId) {
        return decrypt(credentialAad(memberId, credentialId), ciphertext, keyId);
    }

    /** 미확정 등록(Redis, PR-A2)의 비밀을 현재 키로 암호화한다. */
    public Sealed sealEnrollment(long memberId, byte[] secret) {
        return encrypt(enrollmentAad(memberId), secret);
    }

    /** 미확정 등록의 암호문을 복호화한다. 실패 규칙은 {@link #openCredential} 과 같다. */
    public byte[] openEnrollment(long memberId, String ciphertext, String keyId) {
        return decrypt(enrollmentAad(memberId), ciphertext, keyId);
    }

    public String currentKeyId() {
        return current.id();
    }

    /** 저장된 암호문이 현재 키로 만들어졌는지 — 아니면 재암호화 대상이다. */
    public boolean isCurrent(String keyId) {
        return current.id().equals(keyId);
    }

    private Sealed encrypt(byte[] aad, byte[] secret) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, current.spec(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] sealed = cipher.doFinal(secret);
            byte[] stored = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
            return new Sealed(Base64.getEncoder().encodeToString(stored), current.id());
        } catch (GeneralSecurityException failure) {
            throw new MemberException(MemberErrorCode.OTP_UNAVAILABLE);
        }
    }

    private byte[] decrypt(byte[] aad, String ciphertext, String keyId) {
        Key key = keyFor(keyId).orElseThrow(() -> new MemberException(MemberErrorCode.OTP_UNAVAILABLE));
        byte[] stored;
        try {
            stored = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new MemberException(MemberErrorCode.OTP_UNAVAILABLE);
        }
        if (stored.length <= IV_BYTES + TAG_BITS / 8) {
            throw new MemberException(MemberErrorCode.OTP_UNAVAILABLE);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key.spec(), new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
        } catch (GeneralSecurityException rejected) {
            // 원인 예외를 붙이지 않는다 — AEADBadTagException 외에 쓸 정보가 없고, 키 · 암호문을 로그로 끌고 갈 이유가 없다
            throw new MemberException(MemberErrorCode.OTP_UNAVAILABLE);
        }
    }

    private Optional<Key> keyFor(String keyId) {
        if (current.id().equals(keyId)) {
            return Optional.of(current);
        }
        return previous.filter(key -> key.id().equals(keyId));
    }

    static byte[] credentialAad(long memberId, long credentialId) {
        return (AAD_PREFIX + "credential:" + memberId + ":" + credentialId).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] enrollmentAad(long memberId) {
        return (AAD_PREFIX + "enrollment:" + memberId).getBytes(StandardCharsets.US_ASCII);
    }

    /** base64url(SHA-256(키)[0:8]), 패딩 없음 11자. */
    static String keyId(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, KEY_ID_BYTES));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("sha-256 is not available", failure);
        }
    }

    /**
     * 저장값.
     *
     * @param ciphertext base64(IV ‖ 암호문 ‖ 태그)
     * @param keyId      암호화한 키의 식별자
     */
    public record Sealed(String ciphertext, String keyId) {
    }

    private record Key(String id, SecretKeySpec spec) {

        static Key of(byte[] bytes) {
            return new Key(keyId(bytes), new SecretKeySpec(bytes, "AES"));
        }

        @Override
        public String toString() {
            return "Key[id=" + id + "]";
        }
    }
}
