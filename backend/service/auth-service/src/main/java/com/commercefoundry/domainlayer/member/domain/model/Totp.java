package com.commercefoundry.domainlayer.member.domain.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 관리자 OTP 의 TOTP 계산 (RFC 6238, #238) — HMAC-SHA1 · 30초 · 6자리. 인증 앱(Google Authenticator 등)의 기본값과 같아야 해서 바꾸지 않는다.
 * 외부 라이브러리 없이 JDK 만 쓴다.
 *
 * <p>검증은 지금 step 앞뒤 {@link #DEFAULT_WINDOW} step(±30초)까지 받는다 — 휴대폰 시계 오차 · 입력하는 동안 바뀐 코드를 받기 위해서다.
 * 일치한 step 을 돌려주므로, 같은 코드를 다시 쓰지 못하게 하는 판정({@code step > lastUsedTimeStep})은 호출자가 그 step 으로 한다.
 */
public final class Totp {

    /** 비밀 길이 — RFC 4226 §4 권장 160비트(HMAC-SHA1 출력 길이). */
    public static final int SECRET_BYTES = 20;
    public static final int DIGITS = 6;
    public static final long PERIOD_SECONDS = 30;
    public static final int DEFAULT_WINDOW = 1;
    /** 창 상한. 넓힐수록 추측 성공 확률이 (2w+1) 배로 는다. */
    public static final int MAX_WINDOW = 2;

    private static final String ALGORITHM = "HmacSHA1";
    private static final int MODULUS = 1_000_000;

    private Totp() {
    }

    /**
     * 인증 앱이 읽는 등록 URI (Key Uri Format) — {@code otpauth://totp/{issuer}:{account}?secret=..&issuer=..&algorithm=SHA1&digits=6&period=30}.
     * 라벨 · {@code issuer} 값은 퍼센트 인코딩한다(공백은 {@code %20} — {@code +} 를 공백으로 읽지 않는 앱이 있다). 라벨 안의 {@code :} 는 발급자와 계정을 나누는
     * 구분자라 인코딩하지 않고 하나만 둔다 — 그래서 발급자에는 {@code :} 가 올 수 없다({@code AdminOtpProperties} 가 기동에서 막는다).
     * 결과에 비밀이 들어 있으므로 로그에 남기지 않는다.
     *
     * @param secretBase32 {@link Base32#encode} 한 비밀(대문자 · 패딩 없음)
     */
    public static String provisioningUri(String issuer, String accountName, String secretBase32) {
        if (issuer == null || issuer.isBlank() || issuer.contains(":")) {
            throw new IllegalArgumentException("issuer must not be blank or contain ':'");
        }
        if (accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException("account name must not be blank");
        }
        String encodedIssuer = percentEncode(issuer);
        return "otpauth://totp/" + encodedIssuer + ":" + percentEncode(accountName)
            + "?secret=" + secretBase32
            + "&issuer=" + encodedIssuer
            + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 시각이 속한 step — Unix 초를 30초로 나눈 몫(T0 = 0). */
    public static long timeStep(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
    }

    /**
     * step 의 6자리 코드. 앞자리 0 을 채운다. {@code String.format("%06d")} 를 쓰지 않는다 — 기본 로케일의 숫자(예: 태국 · 아랍 숫자)로 찍혀
     * 인증 앱 코드와 영영 맞지 않는다. {@link Integer#toString} 은 로케일과 무관하게 ASCII 숫자다.
     */
    public static String code(byte[] secret, long timeStep) {
        if (timeStep < 0) {
            throw new IllegalArgumentException("time step must not be negative");
        }
        byte[] counter = new byte[Long.BYTES];
        long value = timeStep;
        for (int i = counter.length - 1; i >= 0; i--) {
            counter[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        byte[] hash = hmac(secret, counter);
        // RFC 4226 §5.3 dynamic truncation
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
            | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8)
            | (hash[offset + 3] & 0xff);
        String digits = Integer.toString(binary % MODULUS);
        return "0".repeat(DIGITS - digits.length()) + digits;
    }

    /** {@link #DEFAULT_WINDOW}(±1 step)로 검증한다. */
    public static OptionalLong verify(byte[] secret, String code, long nowStep) {
        return verify(secret, code, nowStep, DEFAULT_WINDOW);
    }

    /**
     * 코드가 {@code nowStep - window ~ nowStep + window} 중 한 step 과 맞으면 그 step 을 돌려준다. 둘 이상 맞으면 가장 늦은 step 이다
     * (재사용 판정을 보수적으로). 6자리 숫자가 아니면 계산하지 않고 비어 있다.
     *
     * <p>비교는 {@link MessageDigest#isEqual} 이고, 맞는 step 을 찾아도 창의 나머지 step 을 끝까지 계산한다 — 응답 시간으로 어느 step 이 맞았는지 드러나지 않게.
     */
    public static OptionalLong verify(byte[] secret, String code, long nowStep, int window) {
        if (window < 0 || window > MAX_WINDOW) {
            throw new IllegalArgumentException("window must be between 0 and " + MAX_WINDOW);
        }
        if (!isWellFormed(code)) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long matched = -1;
        for (long step = nowStep - window; step <= nowStep + window; step++) {
            if (step < 0) {
                continue;
            }
            byte[] expected = code(secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, given)) {
                matched = step;
            }
        }
        return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    private static boolean isWellFormed(String code) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** {@link Mac} 은 스레드 안전하지 않아 호출마다 만든다. 비밀 내용은 예외에 싣지 않는다. */
    private static byte[] hmac(byte[] secret, byte[] message) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("otp secret must not be empty");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("totp hmac is not available", failure);
        }
    }
}
