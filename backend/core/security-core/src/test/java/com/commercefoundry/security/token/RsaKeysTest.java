package com.commercefoundry.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.commercefoundry.security.support.TestKeys;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RsaKeysTest {

    private static final KeyPair KEYS = TestKeys.CURRENT;

    // ── 읽기 성공 ────────────────────────────────────────────────

    @Test
    @DisplayName("아머가 있는 여러 줄 PEM(openssl 출력 그대로)을 읽는다")
    void loadsArmoredPem() {
        assertThat(RsaKeys.loadPublicKey(TestKeys.publicKeyPem(KEYS), RsaKeys.Slot.PUBLIC_KEY)).isEqualTo(KEYS.getPublic());
        assertThat(RsaKeys.loadPrivateKey(TestKeys.privateKeyPem(KEYS), RsaKeys.Slot.PRIVATE_KEY)).isEqualTo(KEYS.getPrivate());
    }

    @Test
    @DisplayName("환경변수의 정본 모양인 PEM 본문 한 줄을 읽는다")
    void loadsSingleLineBody() {
        assertThat(RsaKeys.loadPublicKey(TestKeys.publicKeyLine(KEYS), RsaKeys.Slot.PUBLIC_KEY)).isEqualTo(KEYS.getPublic());
        assertThat(RsaKeys.loadPrivateKey(TestKeys.privateKeyLine(KEYS), RsaKeys.Slot.PRIVATE_KEY)).isEqualTo(KEYS.getPrivate());
    }

    @Test
    @DisplayName("개행이 리터럴 \\n 두 글자로 들어온 값(.env · CI 변수에서 흔하다)을 읽는다")
    void loadsLiteralBackslashN() {
        String pem = TestKeys.publicKeyPem(KEYS).replace("\n", "\\n");

        assertThat(pem).doesNotContain("\n");
        assertThat(RsaKeys.loadPublicKey(pem, RsaKeys.Slot.PUBLIC_KEY)).isEqualTo(KEYS.getPublic());
    }

    @Test
    @DisplayName("CRLF · 탭 · 앞뒤 공백이 섞여도 읽는다")
    void loadsMixedWhitespace() {
        String pem = "  " + TestKeys.publicKeyPem(KEYS).replace("\n", "\r\n\t") + "  ";

        assertThat(RsaKeys.loadPublicKey(pem, RsaKeys.Slot.PUBLIC_KEY)).isEqualTo(KEYS.getPublic());
    }

    // ── 거부 ─────────────────────────────────────────────────────

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "${JWT_PUBLIC_KEY}", "-----BEGIN PUBLIC KEY-----\n-----END PUBLIC KEY-----"})
    @DisplayName("비었거나 플레이스홀더가 풀리지 않았거나 본문이 없으면 환경변수 이름을 알려 준다")
    void rejectsAbsentValue(String value) {
        assertRejected(() -> RsaKeys.loadPublicKey(value, RsaKeys.Slot.PUBLIC_KEY), "JWT_PUBLIC_KEY", "security.jwt.public-key");
    }

    @Test
    @DisplayName("PKCS#1(BEGIN RSA PRIVATE KEY)은 openssl pkcs8 -topk8 -nocrypt 변환 안내와 함께 거부한다")
    void rejectsPkcs1PrivateKey() {
        String pkcs1 = TestKeys.armor("RSA PRIVATE KEY", KEYS.getPrivate().getEncoded());

        IllegalStateException failure = assertRejected(() -> RsaKeys.loadPrivateKey(pkcs1, RsaKeys.Slot.PRIVATE_KEY), "JWT_PRIVATE_KEY", "openssl pkcs8 -topk8 -nocrypt");
        assertNoKeyMaterial(failure, KEYS.getPrivate().getEncoded());
    }

    @Test
    @DisplayName("PKCS#1 공개키(BEGIN RSA PUBLIC KEY)는 openssl pkey -pubout 안내와 함께 거부한다")
    void rejectsPkcs1PublicKey() {
        String pkcs1 = TestKeys.armor("RSA PUBLIC KEY", KEYS.getPublic().getEncoded());

        assertRejected(() -> RsaKeys.loadPublicKey(pkcs1, RsaKeys.Slot.PUBLIC_KEY), "JWT_PUBLIC_KEY", "openssl pkey -pubout");
    }

    @Test
    @DisplayName("공개키 자리에 개인키 PEM 이 들어오면 거부한다 — 개인키가 모든 서비스로 퍼지는 사고를 막는다")
    void rejectsPrivateKeyInPublicSlot() {
        IllegalStateException failure = assertRejected(
            () -> RsaKeys.loadPublicKey(TestKeys.privateKeyPem(KEYS), RsaKeys.Slot.PUBLIC_KEY), "JWT_PUBLIC_KEY", "PRIVATE KEY");
        assertNoKeyMaterial(failure, KEYS.getPrivate().getEncoded());
    }

    @Test
    @DisplayName("아머 없이 개인키 본문이 공개키 자리에, 공개키 본문이 개인키 자리에 들어와도 거부한다")
    void rejectsSwappedBodies() {
        IllegalStateException publicSlot = assertRejected(() -> RsaKeys.loadPublicKey(TestKeys.privateKeyLine(KEYS), RsaKeys.Slot.PUBLIC_KEY), "JWT_PUBLIC_KEY");
        IllegalStateException privateSlot = assertRejected(() -> RsaKeys.loadPrivateKey(TestKeys.publicKeyLine(KEYS), RsaKeys.Slot.PRIVATE_KEY), "JWT_PRIVATE_KEY");

        assertNoKeyMaterial(publicSlot, KEYS.getPrivate().getEncoded());
        assertNoKeyMaterial(privateSlot, KEYS.getPublic().getEncoded());
    }

    @Test
    @DisplayName("2048비트 미만 키는 거부한다")
    void rejectsShortKey() {
        KeyPair weak = TestKeys.generate(1024);

        assertRejected(() -> RsaKeys.loadPublicKey(TestKeys.publicKeyLine(weak), RsaKeys.Slot.PUBLIC_KEY), "JWT_PUBLIC_KEY", "1024", "2048");
        assertRejected(() -> RsaKeys.loadPrivateKey(TestKeys.privateKeyLine(weak), RsaKeys.Slot.PRIVATE_KEY), "JWT_PRIVATE_KEY", "2048");
    }

    @Test
    @DisplayName("base64 가 깨진 본문은 거부하고 메시지에 본문 조각을 넣지 않는다")
    void rejectsBrokenBase64() {
        String broken = TestKeys.publicKeyLine(KEYS).substring(0, 100) + "#%&" + TestKeys.publicKeyLine(KEYS).substring(100);

        IllegalStateException failure = assertRejected(() -> RsaKeys.loadPublicKey(broken, RsaKeys.Slot.PREVIOUS_PUBLIC_KEY), "JWT_PUBLIC_KEY_PREVIOUS", "base64");
        assertNoKeyMaterial(failure, KEYS.getPublic().getEncoded());
    }

    // ── JWK · kid ────────────────────────────────────────────────

    @Test
    @DisplayName("kid 는 공개키의 JWK 썸프린트다 — 읽은 모양과 무관하게 같고, 서명용 JWK 와 검증용 JWK 가 같다")
    void keyIdIsStableThumbprint() throws Exception {
        RSAPublicKey fromPem = RsaKeys.loadPublicKey(TestKeys.publicKeyPem(KEYS), RsaKeys.Slot.PUBLIC_KEY);
        RSAPublicKey fromLine = RsaKeys.loadPublicKey(TestKeys.publicKeyLine(KEYS), RsaKeys.Slot.PUBLIC_KEY);
        RSAPrivateKey privateKey = RsaKeys.loadPrivateKey(TestKeys.privateKeyLine(KEYS), RsaKeys.Slot.PRIVATE_KEY);

        String kid = RsaKeys.toJwk(fromPem).getKeyID();

        assertThat(kid).isEqualTo(new RSAKey.Builder(fromPem).build().computeThumbprint().toString());
        assertThat(RsaKeys.toJwk(fromLine).getKeyID()).isEqualTo(kid);
        assertThat(RsaKeys.toJwk(fromPem, privateKey).getKeyID()).isEqualTo(kid);
        assertThat(RsaKeys.toJwk(TestKeys.publicKey(TestKeys.OTHER)).getKeyID()).isNotEqualTo(kid);
    }

    private static IllegalStateException assertRejected(ThrowingCallable load, String... messageParts) {
        IllegalStateException failure = catchThrowableOfType(IllegalStateException.class, load);
        assertThat(failure).isNotNull();
        assertThat(failure).hasMessageContainingAll(messageParts);
        return failure;
    }

    /** 메시지 · 원인 예외 어디에도 키 본문 조각이 없어야 한다. */
    private static void assertNoKeyMaterial(IllegalStateException failure, byte[] der) {
        String body = Base64.getEncoder().encodeToString(der);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).doesNotContain(body.substring(40, 72), body.substring(body.length() - 40, body.length() - 8));
        }
        assertThat(failure.getCause()).isNull();
    }
}
