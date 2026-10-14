package com.commercefoundry.security.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 환경변수로 받은 RSA 키(PEM)를 읽고 JWK 로 바꾼다 ({@code security-guide.md} 키 배포).
 *
 * <p>입력은 PEM 본문 한 줄이 정본이고, 아머 헤더/푸터가 있거나 개행이 섞이거나 리터럴 {@code \n} 이 들어와도 읽는다.
 * 실패는 {@link IllegalStateException} 이고 메시지에는 프로퍼티·환경변수 이름만 넣는다. <b>키 내용은 메시지·원인 예외 어디에도 넣지 않는다.</b>
 */
public final class RsaKeys {

    /** 이보다 짧은 키는 기동하지 않는다. */
    public static final int MINIMUM_KEY_BITS = 2048;

    private static final Pattern ARMOR = Pattern.compile("-----(?:BEGIN|END) ([A-Z0-9 ]+)-----");
    private static final String PUBLIC_KEY_LABEL = "PUBLIC KEY";
    private static final String PRIVATE_KEY_LABEL = "PRIVATE KEY";
    private static final String PKCS1_PRIVATE_KEY_LABEL = "RSA PRIVATE KEY";
    private static final String PKCS1_PUBLIC_KEY_LABEL = "RSA PUBLIC KEY";
    private static final String LITERAL_NEWLINE = "\\n";
    /** Binder 는 풀리지 않은 {@code ${...}} 를 문자 그대로 남긴다. 환경변수가 없으면 키가 이 문자열이 된다. */
    private static final String PLACEHOLDER_PREFIX = "${";

    /** 키가 들어오는 자리. 메시지에 프로퍼티와 환경변수 이름을 함께 적는다. */
    public enum Slot {

        PUBLIC_KEY("security.jwt.public-key", "JWT_PUBLIC_KEY"),
        PREVIOUS_PUBLIC_KEY("security.jwt.previous-public-key", "JWT_PUBLIC_KEY_PREVIOUS"),
        PRIVATE_KEY("security.jwt.issuance.private-key", "JWT_PRIVATE_KEY");

        private final String property;
        private final String environmentVariable;

        Slot(String property, String environmentVariable) {
            this.property = property;
            this.environmentVariable = environmentVariable;
        }

        public String environmentVariable() {
            return environmentVariable;
        }

        String describe() {
            return "%s(환경변수 %s)".formatted(property, environmentVariable);
        }
    }

    private RsaKeys() {
    }

    /** 값이 없거나 공백뿐이거나 플레이스홀더가 풀리지 않았으면 {@code true} 다. */
    public static boolean isAbsent(String pem) {
        return pem == null || pem.isBlank() || pem.contains(PLACEHOLDER_PREFIX);
    }

    /** X.509 SubjectPublicKeyInfo({@code BEGIN PUBLIC KEY}) RSA 공개키를 읽는다. */
    public static RSAPublicKey loadPublicKey(String pem, Slot slot) {
        byte[] der = decode(pem, slot, PUBLIC_KEY_LABEL);
        RSAPublicKey key;
        try {
            key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | ClassCastException invalid) {
            throw new IllegalStateException(slot.describe() + " 를 RSA 공개키로 읽을 수 없습니다."
                + " openssl pkey -pubout 으로 만든 X.509(BEGIN PUBLIC KEY) 공개키여야 합니다 (security-guide.md 키 생성)");
        }
        requireMinimumSize(key.getModulus().bitLength(), slot);
        return key;
    }

    /** PKCS#8({@code BEGIN PRIVATE KEY}) RSA 개인키를 읽는다. PKCS#1({@code BEGIN RSA PRIVATE KEY})은 변환 안내와 함께 거부한다. */
    public static RSAPrivateKey loadPrivateKey(String pem, Slot slot) {
        byte[] der = decode(pem, slot, PRIVATE_KEY_LABEL);
        RSAPrivateKey key;
        try {
            key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | ClassCastException invalid) {
            throw new IllegalStateException(slot.describe() + " 를 RSA 개인키로 읽을 수 없습니다. PKCS#8(BEGIN PRIVATE KEY) 이어야 합니다."
                + " PKCS#1(BEGIN RSA PRIVATE KEY)이면 openssl pkcs8 -topk8 -nocrypt 로 변환하세요 (security-guide.md 키 생성)");
        }
        requireMinimumSize(key.getModulus().bitLength(), slot);
        return key;
    }

    /** 검증용 JWK. {@code kid} 는 공개키의 JWK 썸프린트(RFC 7638)라 같은 키면 어느 서비스에서 만들어도 같다. */
    public static RSAKey toJwk(RSAPublicKey publicKey) {
        return toJwk(new RSAKey.Builder(publicKey));
    }

    /** 서명용 JWK. 썸프린트는 공개 부분(n, e)으로만 계산하므로 {@code kid} 가 검증용 JWK 와 같다. */
    public static RSAKey toJwk(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        return toJwk(new RSAKey.Builder(publicKey).privateKey(privateKey));
    }

    private static RSAKey toJwk(RSAKey.Builder builder) {
        try {
            return builder.keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint().build();
        } catch (JOSEException failure) {
            throw new IllegalStateException("RSA 키의 JWK 썸프린트를 계산할 수 없습니다", failure);
        }
    }

    private static byte[] decode(String pem, Slot slot, String expectedLabel) {
        if (isAbsent(pem)) {
            throw new IllegalStateException(slot.describe() + " 가 비었거나 플레이스홀더가 풀리지 않았습니다."
                + " PEM 본문 한 줄을 넣으세요 (security-guide.md 키 생성)");
        }
        requireExpectedArmor(pem, slot, expectedLabel);

        String body = ARMOR.matcher(pem).replaceAll("")
            .replace(LITERAL_NEWLINE, "\n")
            .replaceAll("\\s", "");
        if (body.isEmpty()) {
            throw new IllegalStateException(slot.describe() + " 에 PEM 본문이 없습니다 (security-guide.md 키 생성)");
        }
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(slot.describe() + " 가 base64 PEM 본문이 아닙니다 (security-guide.md 키 생성)");
        }
    }

    /** 아머가 있으면 종류를 확인한다. 공개키 자리에 개인키가 들어오는 사고(개인키가 전 서비스로 퍼진다)를 여기서 막는다. */
    private static void requireExpectedArmor(String pem, Slot slot, String expectedLabel) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher matcher = ARMOR.matcher(pem);
        while (matcher.find()) {
            labels.add(matcher.group(1));
        }
        if (labels.contains(PKCS1_PRIVATE_KEY_LABEL)) {
            throw new IllegalStateException(slot.describe() + " 가 PKCS#1(BEGIN RSA PRIVATE KEY) 형식입니다."
                + " openssl pkcs8 -topk8 -nocrypt 로 PKCS#8(BEGIN PRIVATE KEY)로 변환하세요 (security-guide.md 키 생성)");
        }
        if (labels.contains(PKCS1_PUBLIC_KEY_LABEL)) {
            throw new IllegalStateException(slot.describe() + " 가 PKCS#1(BEGIN RSA PUBLIC KEY) 형식입니다."
                + " openssl pkey -pubout 으로 X.509(BEGIN PUBLIC KEY) 공개키를 만드세요 (security-guide.md 키 생성)");
        }
        if (!labels.isEmpty() && !labels.equals(Set.of(expectedLabel))) {
            throw new IllegalStateException(slot.describe() + " 에는 BEGIN " + expectedLabel + " 형식만 넣습니다. 들어온 형식: " + labels
                + (PUBLIC_KEY_LABEL.equals(expectedLabel) ? " — 개인키는 auth-service 의 JWT_PRIVATE_KEY 에만 둡니다" : ""));
        }
    }

    private static void requireMinimumSize(int bits, Slot slot) {
        if (bits < MINIMUM_KEY_BITS) {
            throw new IllegalStateException("%s 의 RSA 키가 %d비트입니다. %d비트 이상이어야 합니다".formatted(slot.describe(), bits, MINIMUM_KEY_BITS));
        }
    }
}
