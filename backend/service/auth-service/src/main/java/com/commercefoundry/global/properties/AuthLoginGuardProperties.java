package com.commercefoundry.global.properties;

import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptPolicy;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 로그인 · 회원가입 시도 제한 ({@code auth.login-guard.*}, #66). 같은 이메일의 시도를 고정 창으로 센다 — 정책 빈은 {@code AuthLoginGuardBeansConfig} 가 만든다.
 *
 * <ul>
 *   <li>{@code max-attempts} · {@code window} 는 로그인(고객 · 관리자 — 값은 같고 창은 따로), {@code sign-up.*} 은 회원가입이다.
 *       꺼져 있어도 검사한다 — 범위는 {@link LoginAttemptPolicy} 와 같고, 벗어나면 기동하지 않는다.</li>
 *   <li>{@code hash-key} 는 정규화한 이메일을 Redis 키로 바꾸는 HMAC 키다({@code AUTH_LOGIN_GUARD_HASH_KEY}, Vault). <b>켤 때만 필수</b>다 —
 *       base64 로 32바이트 이상이어야 하고, 비었거나 {@code ${...}} 가 풀리지 않았으면 기동하지 않는다. 키 없이 해시를 만들면 흔한 이메일 목록을
 *       대입해 되돌릴 수 있다. auth 인스턴스가 모두 같은 값을 써야 같은 이메일이 같은 창에 모인다.</li>
 *   <li>검사는 compact 생성자에서 한다 — Bean Validation 애노테이션을 쓰면 거절된 값(키)이 기동 실패 로그에 찍힌다 (#66 A2 전제 8).</li>
 * </ul>
 *
 * @param enabled     시도를 셀지. 레코드 기본값({@code @DefaultValue})과 application.yml({@code ${AUTH_LOGIN_GUARD_ENABLED:true}}) 모두 켬이다.
 *                    끄면 아무것도 세지 않는다 — 끄는 방법은 환경변수 {@code AUTH_LOGIN_GUARD_ENABLED=false} 하나다
 * @param maxAttempts 로그인 창 하나에 허용하는 시도 수. 기본 5
 * @param window      로그인 창의 길이 — 첫 시도부터 재고, 이어지는 시도가 늘리지 않는다. 기본 15분
 * @param hashKey     base64 HMAC 키. 켤 때만 필수
 * @param signUp      회원가입 창. 기본 5회 · 10분
 */
@ConfigurationProperties(prefix = "auth.login-guard")
public record AuthLoginGuardProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("5") int maxAttempts,
    @DefaultValue("15m") Duration window,
    String hashKey,
    @DefaultValue SignUp signUp
) {

    private static final int MIN_KEY_BYTES = 32;

    public AuthLoginGuardProperties(boolean enabled, int maxAttempts, Duration window, String hashKey) {
        this(enabled, maxAttempts, window, hashKey, SignUp.DEFAULT);
    }

    @ConstructorBinding
    public AuthLoginGuardProperties {
        validateRange("auth.login-guard", maxAttempts, window);
        // 직접 만들 때(테스트) 생략하면 기본값 — 바인딩은 @DefaultValue 로 언제나 값을 넣는다
        if (signUp == null) {
            signUp = SignUp.DEFAULT;
        }
        // 키는 쓸 때만 본다 — 끈 구성에서는 키 없이 뜬다
        if (enabled) {
            decodedKey(hashKey);
        }
    }

    /**
     * 회원가입 창 ({@code auth.login-guard.sign-up.*}). 같은 이메일로 반복하는 가입(409 폴링 · BCrypt 반복)을 센다.
     *
     * @param maxAttempts 창 하나에 허용하는 가입 시도 수. 기본 5
     * @param window      창의 길이. 기본 10분
     */
    public record SignUp(@DefaultValue("5") int maxAttempts, @DefaultValue("10m") Duration window, @DefaultValue ClientIp clientIp) {

        public SignUp(int maxAttempts, Duration window) {
            this(maxAttempts, window, ClientIp.DEFAULT);
        }

        static final SignUp DEFAULT = new SignUp(5, Duration.ofMinutes(10));

        @ConstructorBinding
        public SignUp {
            validateRange("auth.login-guard.sign-up", maxAttempts, window);
            if (clientIp == null) {
                clientIp = ClientIp.DEFAULT;
            }
        }
    }

    /** 공유 IP를 고려해 이메일 한도와 분리한다. 헤더가 없으면 이 차원은 건너뛴다. */
    public record ClientIp(@DefaultValue("30") int maxAttempts, @DefaultValue("10m") Duration window) {

        static final ClientIp DEFAULT = new ClientIp(30, Duration.ofMinutes(10));

        public ClientIp {
            validateRange("auth.login-guard.sign-up.client-ip", maxAttempts, window);
        }
    }

    /** record 기본 {@code toString} 은 키를 평문으로 싣는다 — 바인딩 오류 · 디버그 로그로 새지 않게 가린다. */
    @Override
    public String toString() {
        return "AuthLoginGuardProperties[enabled=" + enabled + ", maxAttempts=" + maxAttempts + ", window=" + window + ", hashKey=****, signUp=" + signUp + "]";
    }

    /** HMAC 키 바이트. 규칙에 맞지 않으면 {@link IllegalArgumentException} — 값 자체는 메시지에 넣지 않는다. */
    public byte[] hashKeyBytes() {
        return decodedKey(hashKey);
    }

    private static void validateRange(String prefix, int maxAttempts, Duration window) {
        if (maxAttempts < 1 || maxAttempts > LoginAttemptPolicy.MAX_ATTEMPTS_CAP) {
            throw new IllegalStateException(prefix + ".max-attempts 는 1 이상 " + LoginAttemptPolicy.MAX_ATTEMPTS_CAP + " 이하여야 합니다");
        }
        if (window == null || window.compareTo(LoginAttemptPolicy.MIN_WINDOW) < 0 || window.compareTo(LoginAttemptPolicy.MAX_WINDOW) > 0) {
            throw new IllegalStateException(prefix + ".window 는 1분 이상 1시간 이하여야 합니다");
        }
    }

    private static byte[] decodedKey(String hashKey) {
        if (hashKey == null || hashKey.isBlank() || hashKey.contains("${")) {
            throw new IllegalArgumentException("auth.login-guard.hash-key(AUTH_LOGIN_GUARD_HASH_KEY)가 비었거나 풀리지 않았습니다.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(hashKey.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("auth.login-guard.hash-key(AUTH_LOGIN_GUARD_HASH_KEY)는 base64 여야 합니다.");
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("auth.login-guard.hash-key(AUTH_LOGIN_GUARD_HASH_KEY)는 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다.");
        }
        return bytes;
    }
}
