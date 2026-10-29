package com.commercefoundry.global.properties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 관리자 OTP ({@code auth.admin-otp.*}, #238). 아래 중 하나라도 어긋나면 <b>기동하지 않는다.</b> 메시지에는 프로퍼티 · 환경변수 이름만 넣고 키 내용은 넣지 않는다.
 *
 * <ul>
 *   <li>{@code secret-key}({@code AUTH_OTP_SECRET_KEY}, Vault)는 <b>항상 필수</b>다 — OTP 를 등록한 관리자가 없어도. 비었거나 {@code ${...}} 가 풀리지 않았거나
 *       base64 가 아니거나 디코드해 정확히 32바이트(AES-256 키)가 아니면 실패한다. {@code openssl rand -base64 32} 로 만든다</li>
 *   <li>{@code previous-secret-key}({@code AUTH_OTP_SECRET_KEY_PREVIOUS})는 키 회전 중에만 채운다. 비었으면 없다. 채웠다면 같은 규칙이고, 현재 키와 같으면 실패한다
 *       (회전을 잘못 적은 것이다)</li>
 *   <li>{@code issuer} 는 인증 앱에 보이는 발급자 이름 — otpauth URI 라벨의 구분자인 {@code :} 를 넣을 수 없다</li>
 *   <li>{@code enrollment-ttl} 은 미확정 등록(Redis)의 수명, 1분~1시간. {@code challenge-ttl} 은 로그인 2단계 챌린지의 수명, 1분~10분</li>
 *   <li>디코드한 키에 {@value #TEST_ONLY_MARKER} 가 들어 있으면(저장소에 공개된 테스트 키) 실패한다. {@code allow-test-only-key: true} 는 테스트 설정
 *       ({@code application-test.yml} · 테스트 코드)에만 둔다 — 운영 · 개발 yml · 환경변수에 넣지 않는다 (secret-management.md)</li>
 *   <li>검사는 compact 생성자에서 한다 — Bean Validation 애노테이션을 쓰면 거절된 값(키)이 기동 실패 로그에 찍힌다</li>
 * </ul>
 *
 * @param secretKey         현재 비밀 암호화 키 (base64, 32바이트)
 * @param previousSecretKey 키 회전 중의 이전 키. 복호화에만 쓴다
 * @param issuer            otpauth URI 발급자
 * @param enrollmentTtl     미확정 등록 수명. 기본 15분
 * @param challengeTtl      로그인 2단계 챌린지 수명. 기본 5분
 * @param allowTestOnlyKey  저장소의 테스트 키를 받을지. 테스트 설정만 켠다. 기본 끔
 */
@ConfigurationProperties(prefix = "auth.admin-otp")
public record AdminOtpProperties(
    String secretKey,
    String previousSecretKey,
    @DefaultValue("Commerce Foundry Admin") String issuer,
    @DefaultValue("15m") Duration enrollmentTtl,
    @DefaultValue("5m") Duration challengeTtl,
    @DefaultValue("false") boolean allowTestOnlyKey
) {

    /** AES-256 키 길이. */
    public static final int KEY_BYTES = 32;

    /** 저장소에 커밋된 테스트 키(application-test.yml · 테스트 코드)는 모두 디코드하면 이 문자열을 품는다. */
    static final String TEST_ONLY_MARKER = "test-only";

    private static final String PLACEHOLDER_PREFIX = "${";
    private static final String CURRENT = "auth.admin-otp.secret-key(AUTH_OTP_SECRET_KEY)";
    private static final String PREVIOUS = "auth.admin-otp.previous-secret-key(AUTH_OTP_SECRET_KEY_PREVIOUS)";
    private static final Duration MIN_TTL = Duration.ofMinutes(1);
    private static final Duration MAX_ENROLLMENT_TTL = Duration.ofHours(1);
    private static final Duration MAX_CHALLENGE_TTL = Duration.ofMinutes(10);

    public AdminOtpProperties {
        byte[] current = decode(secretKey, CURRENT);
        rejectTestOnlyKey(current, CURRENT, allowTestOnlyKey);
        if (!isAbsent(previousSecretKey)) {
            byte[] previous = decode(previousSecretKey, PREVIOUS);
            if (Arrays.equals(current, previous)) {
                throw new IllegalStateException(PREVIOUS + " 가 현재 키와 같습니다. 회전 중이 아니면 비워 둡니다 (secret-management.md)");
            }
            rejectTestOnlyKey(previous, PREVIOUS, allowTestOnlyKey);
        }
        if (issuer == null || issuer.isBlank() || issuer.contains(PLACEHOLDER_PREFIX) || issuer.contains(":")) {
            throw new IllegalStateException("auth.admin-otp.issuer(AUTH_OTP_ISSUER)가 비었거나 ':' 를 포함합니다");
        }
        issuer = issuer.strip();
        validateTtl("auth.admin-otp.enrollment-ttl", enrollmentTtl, MAX_ENROLLMENT_TTL, "1시간");
        validateTtl("auth.admin-otp.challenge-ttl", challengeTtl, MAX_CHALLENGE_TTL, "10분");
    }

    /** 현재 키 바이트 — 암호화 · 복호화. */
    public byte[] secretKeyBytes() {
        return decode(secretKey, CURRENT);
    }

    /** 이전 키 바이트 — 회전 중에만 있다. 복호화에만 쓴다. */
    public Optional<byte[]> previousSecretKeyBytes() {
        return isAbsent(previousSecretKey) ? Optional.empty() : Optional.of(decode(previousSecretKey, PREVIOUS));
    }

    /** record 기본 {@code toString} 은 키를 평문으로 싣는다. */
    @Override
    public String toString() {
        return "AdminOtpProperties[secretKey=****, previousSecretKey=" + (isAbsent(previousSecretKey) ? "(none)" : "****")
            + ", issuer=" + issuer + ", enrollmentTtl=" + enrollmentTtl + ", challengeTtl=" + challengeTtl
            + ", allowTestOnlyKey=" + allowTestOnlyKey + "]";
    }

    private static boolean isAbsent(String value) {
        return value == null || value.isBlank();
    }

    /** 공개 저장소의 테스트 키는 누구나 안다 — 그 키로 암호화한 비밀은 평문과 같다. 메시지에 키 내용을 싣지 않는다. */
    private static void rejectTestOnlyKey(byte[] key, String slot, boolean allowTestOnlyKey) {
        if (!allowTestOnlyKey && new String(key, StandardCharsets.ISO_8859_1).contains(TEST_ONLY_MARKER)) {
            throw new IllegalStateException(slot + " 가 저장소에 공개된 테스트 키입니다. openssl rand -base64 32 로 새로 만든 값을 넣으세요");
        }
    }

    private static void validateTtl(String property, Duration ttl, Duration max, String maxLabel) {
        if (ttl == null || ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(max) > 0) {
            throw new IllegalStateException(property + " 는 1분 이상 " + maxLabel + " 이하여야 합니다");
        }
    }

    /** 실패 메시지 · 원인 예외에 키 내용을 싣지 않는다. 이전 키 자리의 풀리지 않은 플레이스홀더도 실패다 — 비울 때는 빈 값으로 둔다. */
    private static byte[] decode(String value, String slot) {
        if (isAbsent(value) || value.contains(PLACEHOLDER_PREFIX)) {
            throw new IllegalStateException(slot + " 가 비었거나 플레이스홀더가 풀리지 않았습니다. openssl rand -base64 32 로 만든 값을 넣으세요");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException(slot + " 를 base64 로 읽을 수 없습니다");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("%s 를 디코드하면 %d바이트입니다. AES-256 키라 %d바이트여야 합니다 — openssl rand -base64 32"
                .formatted(slot, bytes.length, KEY_BYTES));
        }
        return bytes;
    }
}
