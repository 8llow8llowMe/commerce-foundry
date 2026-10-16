package com.commercefoundry.security.support;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * 테스트용 RSA 키를 실행 중에 만든다. 공개 저장소라 키 파일·PEM 본문을 커밋하지 않는다.
 * 생성 비용(2048비트 수십 ms)을 줄이려고 같은 JVM 의 테스트가 공유한다.
 * security-core 의 테스트 픽스처라 서비스 · 게이트웨이 테스트도 같은 키를 쓴다.
 */
public final class TestKeys {

    /** 발급기가 쓰는 현재 키. 컨텍스트 테스트의 {@code security.jwt.*} 도 이 키다 ({@link SecurityTestPropertiesInitializer}). */
    public static final KeyPair CURRENT = generate(2048);
    /** 키 교체 중의 이전 키. */
    public static final KeyPair PREVIOUS = generate(2048);
    /** 우리 것이 아닌 키 — 위조 서명용. */
    public static final KeyPair OTHER = generate(2048);

    private TestKeys() {
    }

    public static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static RSAPublicKey publicKey(KeyPair keyPair) {
        return (RSAPublicKey) keyPair.getPublic();
    }

    public static RSAPrivateKey privateKey(KeyPair keyPair) {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    /** 환경변수의 정본 모양 — 아머 없는 PEM 본문 한 줄. */
    public static String publicKeyLine(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public static String privateKeyLine(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    /** {@code openssl pkey -pubout} 이 쓰는 모양 — 아머와 64자 줄바꿈. */
    public static String publicKeyPem(KeyPair keyPair) {
        return armor("PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    /** PKCS#8 개인키 PEM. */
    public static String privateKeyPem(KeyPair keyPair) {
        return armor("PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    public static String armor(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }
}
