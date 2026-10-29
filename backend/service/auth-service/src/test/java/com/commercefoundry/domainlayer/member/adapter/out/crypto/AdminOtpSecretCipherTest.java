package com.commercefoundry.domainlayer.member.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher.Sealed;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.global.properties.AdminOtpProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 값은 전부 테스트 전용이다. 비밀이 아니다. */
public class AdminOtpSecretCipherTest {

    /** base64("auth-admin-otp-test-only-key-32b") — application-test.yml 과 같다. */
    public static final String KEY = "YXV0aC1hZG1pbi1vdHAtdGVzdC1vbmx5LWtleS0zMmI=";
    /** base64("auth-admin-otp-test-only-prev-32"). */
    public static final String OTHER_KEY = "YXV0aC1hZG1pbi1vdHAtdGVzdC1vbmx5LXByZXYtMzI=";

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final long MEMBER_ID = 42L;
    private static final long CREDENTIAL_ID = 7001L;

    /** 테스트 키라 {@code allowTestOnlyKey} 를 켠다. */
    public static AdminOtpSecretCipher cipher(String current, String previous) {
        return new AdminOtpSecretCipher(new AdminOtpProperties(current, previous, "Test Issuer", Duration.ofMinutes(15), Duration.ofMinutes(5), true));
    }

    @Test
    @DisplayName("확정 자격 — 같은 회원 · 같은 자격으로 복호화하면 원문이다. 저장값은 base64(IV 12 ‖ 암호문 20 ‖ 태그 16), 매번 IV 가 달라 암호문도 다르다")
    void roundTrip() {
        AdminOtpSecretCipher cipher = cipher(KEY, null);

        Sealed first = cipher.sealCredential(MEMBER_ID, CREDENTIAL_ID, SECRET);
        Sealed second = cipher.sealCredential(MEMBER_ID, CREDENTIAL_ID, SECRET);

        assertThat(cipher.openCredential(MEMBER_ID, CREDENTIAL_ID, first.ciphertext(), first.keyId())).isEqualTo(SECRET);
        assertThat(Base64.getDecoder().decode(first.ciphertext())).hasSize(12 + SECRET.length + 16);
        assertThat(first.ciphertext()).hasSizeLessThanOrEqualTo(128).isNotEqualTo(second.ciphertext());
        assertThat(first.keyId()).isEqualTo(cipher.currentKeyId());
        assertThat(cipher.isCurrent(first.keyId())).isTrue();
    }

    @Test
    @DisplayName("미확정 등록 — 회원 단위 AAD 로 왕복한다")
    void enrollmentRoundTrip() {
        AdminOtpSecretCipher cipher = cipher(KEY, null);
        Sealed sealed = cipher.sealEnrollment(MEMBER_ID, SECRET);

        assertThat(cipher.openEnrollment(MEMBER_ID, sealed.ciphertext(), sealed.keyId())).isEqualTo(SECRET);
        assertUnavailable(() -> cipher.openEnrollment(MEMBER_ID + 1, sealed.ciphertext(), sealed.keyId()));
    }

    @Test
    @DisplayName("AAD 형식 — admin-otp:v1:credential:{memberId}:{credentialId} · admin-otp:v1:enrollment:{memberId}")
    void aadFormat() {
        assertThat(new String(AdminOtpSecretCipher.credentialAad(42L, 7001L), StandardCharsets.US_ASCII)).isEqualTo("admin-otp:v1:credential:42:7001");
        assertThat(new String(AdminOtpSecretCipher.enrollmentAad(42L), StandardCharsets.US_ASCII)).isEqualTo("admin-otp:v1:enrollment:42");
    }

    @Test
    @DisplayName("AAD 가 다르면 거절한다 — 다른 관리자 행 · 같은 관리자의 다른(옛) 자격 행으로 옮겨 붙인 암호문은 503(AUTH_039)")
    void rejectsOtherMemberOrCredential() {
        AdminOtpSecretCipher cipher = cipher(KEY, null);
        Sealed sealed = cipher.sealCredential(MEMBER_ID, CREDENTIAL_ID, SECRET);

        assertUnavailable(() -> cipher.openCredential(MEMBER_ID + 1, CREDENTIAL_ID, sealed.ciphertext(), sealed.keyId()));
        assertUnavailable(() -> cipher.openCredential(MEMBER_ID, CREDENTIAL_ID + 1, sealed.ciphertext(), sealed.keyId()));
    }

    @Test
    @DisplayName("용도가 다르면 거절한다 — 미확정 등록 암호문을 확정 자격으로, 그 반대로 쓸 수 없다")
    void rejectsOtherPurpose() {
        AdminOtpSecretCipher cipher = cipher(KEY, null);
        Sealed enrollment = cipher.sealEnrollment(MEMBER_ID, SECRET);
        Sealed credential = cipher.sealCredential(MEMBER_ID, CREDENTIAL_ID, SECRET);

        assertUnavailable(() -> cipher.openCredential(MEMBER_ID, CREDENTIAL_ID, enrollment.ciphertext(), enrollment.keyId()));
        assertUnavailable(() -> cipher.openEnrollment(MEMBER_ID, credential.ciphertext(), credential.keyId()));
    }

    @Test
    @DisplayName("암호문을 한 바이트라도 바꾸거나 깨진 base64 · 짧은 값이면 거절한다")
    void rejectsTampered() {
        AdminOtpSecretCipher cipher = cipher(KEY, null);
        Sealed sealed = cipher.sealCredential(MEMBER_ID, CREDENTIAL_ID, SECRET);
        byte[] raw = Base64.getDecoder().decode(sealed.ciphertext());
        raw[raw.length - 1] ^= 1;

        assertUnavailable(() -> cipher.openCredential(MEMBER_ID, CREDENTIAL_ID, Base64.getEncoder().encodeToString(raw), sealed.keyId()));
        assertUnavailable(() -> cipher.openCredential(MEMBER_ID, CREDENTIAL_ID, "not base64!!", sealed.keyId()));
        assertUnavailable(() -> cipher.openCredential(MEMBER_ID, CREDENTIAL_ID, Base64.getEncoder().encodeToString(new byte[28]), sealed.keyId()));
        assertUnavailable(() -> cipher.openCredential(MEMBER_ID, CREDENTIAL_ID, null, sealed.keyId()));
    }

    @Test
    @DisplayName("회전 — 이전 키로 만든 암호문을 previous 로 복호화하고, 현재 키가 아니라고 알려 준다")
    void decryptsWithPreviousKey() {
        Sealed sealedWithOld = cipher(OTHER_KEY, null).sealCredential(MEMBER_ID, CREDENTIAL_ID, SECRET);
        AdminOtpSecretCipher rotated = cipher(KEY, OTHER_KEY);

        assertThat(rotated.openCredential(MEMBER_ID, CREDENTIAL_ID, sealedWithOld.ciphertext(), sealedWithOld.keyId())).isEqualTo(SECRET);
        assertThat(rotated.isCurrent(sealedWithOld.keyId())).isFalse();
        assertThat(rotated.sealCredential(MEMBER_ID, CREDENTIAL_ID, SECRET).keyId()).isEqualTo(rotated.currentKeyId()).isNotEqualTo(sealedWithOld.keyId());
    }

    @Test
    @DisplayName("key_id 가 현재 · 이전 어느 키와도 맞지 않으면 AUTH_039 — previous 를 비운 뒤 남은 옛 행")
    void rejectsUnknownKeyId() {
        Sealed sealedWithOld = cipher(OTHER_KEY, null).sealCredential(MEMBER_ID, CREDENTIAL_ID, SECRET);
        AdminOtpSecretCipher withoutPrevious = cipher(KEY, "");

        assertUnavailable(() -> withoutPrevious.openCredential(MEMBER_ID, CREDENTIAL_ID, sealedWithOld.ciphertext(), sealedWithOld.keyId()));
        assertUnavailable(() -> withoutPrevious.openCredential(MEMBER_ID, CREDENTIAL_ID, sealedWithOld.ciphertext(), null));
    }

    @Test
    @DisplayName("key_id 는 base64url(SHA-256(키) 앞 8바이트) 11자다 — 키마다 다르고 같은 키면 같다")
    void keyIdDerivation() throws Exception {
        byte[] key = Base64.getDecoder().decode(KEY);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(key);
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 8));

        assertThat(AdminOtpSecretCipher.keyId(key)).isEqualTo(expected).hasSize(11).matches("[A-Za-z0-9_-]{11}");
        assertThat(cipher(KEY, null).currentKeyId()).isEqualTo(expected);
        assertThat(cipher(OTHER_KEY, null).currentKeyId()).isNotEqualTo(expected);
    }

    private static void assertUnavailable(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(MemberException.class)
            .extracting("errorCode").isEqualTo(MemberErrorCode.OTP_UNAVAILABLE);
    }
}
