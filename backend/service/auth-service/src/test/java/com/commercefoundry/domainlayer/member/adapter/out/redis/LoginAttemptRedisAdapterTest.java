package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.AttemptKind;
import com.commercefoundry.domainlayer.member.domain.model.LoginAttemptOutcome;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import com.commercefoundry.support.TestLoginGuard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 컨테이너(requirepass)에서 시도 계수 Lua 스크립트 · 키 · TTL 을 실제로 돌린다 ({@code coding-conventions.md} §9-7).
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 */
@Testcontainers
@EnabledWithDockerOrOnCi
class LoginAttemptRedisAdapterTest {

    /** 테스트 전용 값이다. 비밀이 아니다. */
    private static final String PASSWORD = "auth-redis-test-only";
    private static final Duration WINDOW = Duration.ofMinutes(15);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
        .withCommand("redis-server", "--requirepass", PASSWORD)
        .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final LoginAttemptKeyHasher hasher = new LoginAttemptKeyHasher(TestLoginGuard.properties());
    private LoginAttemptRedisAdapter adapter;

    @BeforeAll
    static void connect() {
        connectionFactory = connectionFactory(PASSWORD);
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        adapter = new LoginAttemptRedisAdapter(redisTemplate, hasher);
    }

    @Test
    @DisplayName("첫 시도는 1 과 창 전체 TTL, 둘째 시도는 2 이고 TTL 을 늘리지 않는다(고정 창)")
    void firstAttemptSetsWindowAndNextDoesNotExtend() {
        String email = email();

        LoginAttemptOutcome first = adapter.record(AttemptKind.LOGIN, email, WINDOW);

        assertThat(first.count()).isEqualTo(1);
        assertThat(first.retryAfter()).isBetween(WINDOW.minusSeconds(5), WINDOW);
        assertTtlAbout(loginKey(email), WINDOW);

        // 창이 이미 줄어든 상태를 만든다 — 둘째 시도가 창을 다시 걸면 15분으로 돌아간다
        redisTemplate.expire(loginKey(email), Duration.ofMinutes(1));
        LoginAttemptOutcome second = adapter.record(AttemptKind.LOGIN, email, WINDOW);

        assertThat(second.count()).isEqualTo(2);
        assertThat(second.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(redisTemplate.getExpire(loginKey(email), TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1).toMillis());
        assertThat(redisTemplate.opsForValue().get(loginKey(email))).isEqualTo("2");
    }

    @Test
    @DisplayName("reset 하면 키가 지워지고 다음 시도는 1 부터 창 전체로 다시 센다")
    void resetStartsOver() {
        String email = email();
        adapter.record(AttemptKind.LOGIN, email, WINDOW);
        adapter.record(AttemptKind.LOGIN, email, WINDOW);

        adapter.reset(AttemptKind.LOGIN, email);

        assertThat(redisTemplate.hasKey(loginKey(email))).isFalse();
        LoginAttemptOutcome next = adapter.record(AttemptKind.LOGIN, email, WINDOW);
        assertThat(next.count()).isEqualTo(1);
        assertThat(next.retryAfter()).isBetween(WINDOW.minusSeconds(5), WINDOW);
    }

    @Test
    @DisplayName("없는 키 reset 은 아무 일도 없다")
    void resetMissingKey() {
        String email = email();

        assertThatCode(() -> adapter.reset(AttemptKind.SIGN_UP, email)).doesNotThrowAnyException();
        assertThat(redisTemplate.hasKey(signUpKey(email))).isFalse();
    }

    @Test
    @DisplayName("로그인 · 회원가입은 키가 따로다 — 한쪽 시도가 다른 쪽 수를 올리지 않고, 한쪽 reset 이 다른 쪽을 지우지 않는다")
    void kindsUseSeparateKeys() {
        String email = email();
        adapter.record(AttemptKind.LOGIN, email, WINDOW);
        adapter.record(AttemptKind.LOGIN, email, WINDOW);

        assertThat(adapter.record(AttemptKind.SIGN_UP, email, Duration.ofMinutes(10)).count()).isEqualTo(1);
        assertTtlAbout(signUpKey(email), Duration.ofMinutes(10));

        adapter.reset(AttemptKind.SIGN_UP, email);
        assertThat(redisTemplate.opsForValue().get(loginKey(email))).isEqualTo("2");
    }

    @Test
    @DisplayName("대소문자 · 앞뒤 공백만 다른 이메일은 한 키 · 한 수로 모인다 — 변형마다 새 창이 열리지 않고, 변형으로 reset 해도 그 키가 지워진다")
    void emailVariantsShareOneKey() {
        String email = email();
        String upper = email.toUpperCase(Locale.ROOT);
        String mixed = Character.toUpperCase(email.charAt(0)) + email.substring(1);

        assertThat(adapter.record(AttemptKind.LOGIN, mixed + " ", WINDOW).count()).isEqualTo(1);
        assertThat(adapter.record(AttemptKind.LOGIN, " " + upper, WINDOW).count()).isEqualTo(2);
        assertThat(adapter.record(AttemptKind.LOGIN, email, WINDOW).count()).isEqualTo(3);

        assertThat(redisTemplate.opsForValue().get(loginKey(email))).isEqualTo("3");
        assertThat(redisTemplate.hasKey(loginKey(upper))).as("변형 그대로의 키는 생기지 않는다").isFalse();
        assertThat(redisTemplate.hasKey(loginKey(mixed + " "))).isFalse();

        adapter.reset(AttemptKind.LOGIN, " " + mixed);
        assertThat(redisTemplate.hasKey(loginKey(email))).isFalse();
    }

    @Test
    @DisplayName("같은 이메일 32회 동시 기록 — 돌려받은 수는 정확히 1..32, 저장 값은 32, 창은 한 번만 걸려 TTL · 남은 시간이 창을 넘지 않는다")
    void concurrentRecordsCountExactly() throws Exception {
        String email = email();
        int attempts = 32;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<LoginAttemptOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return adapter.record(AttemptKind.LOGIN, email, WINDOW);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<LoginAttemptOutcome> outcomes = new ArrayList<>();
            for (Future<LoginAttemptOutcome> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }

            // INCR 이 원자라 같은 수를 두 번 받거나 빠뜨린 수가 없다
            assertThat(outcomes.stream().map(LoginAttemptOutcome::count).sorted().toList())
                .containsExactlyElementsOf(LongStream.rangeClosed(1, attempts).boxed().toList());
            assertThat(outcomes).extracting(LoginAttemptOutcome::retryAfter).allSatisfy(retryAfter -> assertThat(retryAfter).isPositive().isLessThanOrEqualTo(WINDOW));
            assertThat(redisTemplate.opsForValue().get(loginKey(email))).isEqualTo(String.valueOf(attempts));
            assertThat(redisTemplate.getExpire(loginKey(email), TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(WINDOW.toMillis());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("TTL 없이 남은 키(영구 키)는 다음 시도에서 창을 다시 걸고 창 전체를 돌려준다 — 그 이메일이 영영 묶이지 않는다")
    void persistentKeyGetsWindow() {
        String email = email();
        redisTemplate.opsForValue().set(loginKey(email), "3");

        LoginAttemptOutcome outcome = adapter.record(AttemptKind.LOGIN, email, WINDOW);

        assertThat(outcome).isEqualTo(new LoginAttemptOutcome(4, WINDOW));
        assertTtlAbout(loginKey(email), WINDOW);
    }

    @Test
    @DisplayName("Redis 에 이메일 원문이 남지 않는다 — 키는 auth:login-attempt:{22자 HMAC} 이다")
    void storedKeyHasNoRawEmail() {
        String email = email();
        adapter.record(AttemptKind.LOGIN, email, WINDOW);
        adapter.record(AttemptKind.SIGN_UP, email, WINDOW);

        Set<String> keys = redisTemplate.keys("auth:*-attempt:*");
        String localPart = email.substring(0, email.indexOf('@'));

        assertThat(keys).contains(loginKey(email), signUpKey(email));
        assertThat(keys).allSatisfy(key -> assertThat(key)
            .matches("auth:(login|sign-up)-attempt:[A-Za-z0-9_-]{22}")
            .doesNotContain("@", localPart, "example"));
    }

    @Test
    @DisplayName("같은 키에 다른 타입 값이 있으면(WRONGTYPE) 스크립트 오류이고 AUTH_013 이다")
    void wrongTypeBecomesUnavailable() {
        String email = email();
        redisTemplate.opsForHash().put(loginKey(email), "field", "value");
        redisTemplate.expire(loginKey(email), Duration.ofMinutes(1));

        assertThatThrownBy(() -> adapter.record(AttemptKind.LOGIN, email, WINDOW))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("비밀번호가 틀린 접속은 AUTH_013 이다 — Redis 오류가 도메인 503 으로 바뀐다")
    void wrongPasswordBecomesUnavailable() {
        LettuceConnectionFactory wrong = connectionFactory("wrong-password");
        try {
            LoginAttemptRedisAdapter failing = new LoginAttemptRedisAdapter(new StringRedisTemplate(wrong), hasher);

            assertThatThrownBy(() -> failing.record(AttemptKind.LOGIN, email(), WINDOW))
                .isInstanceOf(MemberException.class)
                .extracting(exception -> ((MemberException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        } finally {
            wrong.destroy();
        }
    }

    private static LettuceConnectionFactory connectionFactory(String password) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        standalone.setPassword(password);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5)).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    /** 테스트마다 다른 이메일 — 컨테이너를 같이 쓰는 테스트끼리 키가 겹치지 않게. */
    private static String email() {
        return "member-" + UUID.randomUUID() + "@example.com";
    }

    private String loginKey(String email) {
        return "auth:login-attempt:" + hasher.hash(email);
    }

    private String signUpKey(String email) {
        return "auth:sign-up-attempt:" + hasher.hash(email);
    }

    /** TTL 이 있고(영구 키 아님) 기대값에서 1분 안이다. */
    private static void assertTtlAbout(String key, Duration expected) {
        Long ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).as(key + " TTL").isNotNull().isPositive().isBetween(expected.minusMinutes(1).toMillis(), expected.toMillis());
    }
}
