package com.commercefoundry.storage.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.ObjectStorageClients;
import com.commercefoundry.storage.client.StorageException;
import com.commercefoundry.storage.client.StorageException.Reason;
import com.commercefoundry.storage.properties.StorageProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class StorageStartupGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(systemEnvironment(Map.of()))
        .withUserConfiguration(StoragePropertiesConfig.class, StorageConfigurer.class);

    // ── 꺼짐(기본) ──────────────────────────────────────────────

    @Test
    @DisplayName("storage.* 가 하나도 없으면 꺼진 채 기동하고, 클라이언트 빈은 있지만 I/O 가 전부 DISABLED 다")
    void startsDisabledWithoutAnyValue() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StorageProperties.class).enabled()).isFalse();

            ObjectStorageClient client = context.getBean(ObjectStorageClient.class);
            assertDisabled(() -> client.presignPut("uploads/a.jpg", "image/jpeg", 10));
            assertDisabled(() -> client.head("uploads/a.jpg"));
            assertDisabled(() -> client.readRange("uploads/a.jpg", 12, "\"etag\""));
            assertDisabled(() -> client.copy("uploads/a.jpg", "products/1/2.jpg", "image/jpeg", null, "\"etag\""));
            assertDisabled(() -> client.delete("uploads/a.jpg"));
            assertDisabled(() -> client.put("migrated/a.jpg", new byte[] {1}, "image/jpeg", null));
            // 공개 주소가 없으면 공개 URL 도 만들 수 없다
            assertDisabled(() -> client.publicUrlOf("products/1/2.jpg"));
        });
    }

    @Test
    @DisplayName("꺼져 있어도 공개 주소가 있으면 공개 URL 은 만든다 — 끝 '/' 를 정규화한다")
    void buildsPublicUrlWhileDisabled() {
        runner.withPropertyValues("storage.public-base-url=https://cdn.example.test/")
            .run(context -> {
                ObjectStorageClient client = context.getBean(ObjectStorageClient.class);

                assertThat(client.publicUrlOf("products/1/2.jpg")).isEqualTo("https://cdn.example.test/products/1/2.jpg");
                assertDisabled(() -> client.head("products/1/2.jpg"));
            });
    }

    @Test
    @DisplayName("꺼져 있으면 Vault 가 비어 ${...} 가 풀리지 않아도 기동한다 — 공개 주소는 없는 것으로 본다")
    void startsDisabledWithUnresolvedPlaceholders() {
        runner.withPropertyValues(
                "storage.endpoint=${STORAGE_ENDPOINT}", "storage.secret-key=${STORAGE_SECRET_KEY}", "storage.public-base-url=${STORAGE_PUBLIC_BASE_URL}")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertDisabled(() -> context.getBean(ObjectStorageClient.class).publicUrlOf("products/1/2.jpg"));
            });
    }

    @Test
    @DisplayName("빈 환경변수 STORAGE_ENABLED= 는 꺼짐이다 — 바인딩이 실패하지 않는다")
    void treatsEmptyEnabledAsDisabled() {
        runner.withInitializer(systemEnvironment(Map.of("STORAGE_ENABLED", "")))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(StorageProperties.class).enabled()).isFalse();
            });
    }

    @Test
    @DisplayName("꺼져 있어도 채운 공개 주소가 https 가 아니면 기동하지 않는다 — 조용히 틀린 URL 을 내지 않게")
    void failsDisabledWithInvalidPublicBaseUrl() {
        runner.withPropertyValues("storage.public-base-url=http://cdn.example.test")
            .run(context -> assertStartupFailure(context, "storage.public-base-url", "STORAGE_PUBLIC_BASE_URL", "https"));
    }

    @Test
    @DisplayName("storage.enabled 형식이 틀리면 기동하지 않는다")
    void failsWithMalformedEnabled() {
        runner.withPropertyValues("storage.enabled=sometimes")
            .run(context -> assertStartupFailure(context, "storage.enabled", "STORAGE_ENABLED"));
    }

    // ── 켜짐 ────────────────────────────────────────────────────

    @Test
    @DisplayName("값이 다 있으면 기동하고 S3 호환 클라이언트가 오프라인으로 서명한다")
    void startsEnabledWithValidSettings() {
        runner.withPropertyValues(enabled())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(StorageStartupGuard.class);

                StorageProperties properties = context.getBean(StorageProperties.class);
                assertThat(properties.publicBaseUrl()).isEqualTo("https://cdn.example.test");
                assertThat(properties.presignTtl()).isEqualTo(Duration.ofMinutes(5));
                assertThat(properties.apiCallTimeout()).isEqualTo(Duration.ofSeconds(8));
                assertThat(properties.pathStyleAccess()).isTrue();
                assertThat(properties.toString()).doesNotContain("test-access-key", "test-secret-key");

                ObjectStorageClient client = context.getBean(ObjectStorageClient.class);
                assertThat(client.presignPut("uploads/a.jpg", "image/jpeg", 10).uploadUrl()).startsWith("http://localhost:9000/test-bucket/uploads/a.jpg?");
                assertThat(client.publicUrlOf("products/1/2.jpg")).isEqualTo("https://cdn.example.test/products/1/2.jpg");
            });
    }

    @Test
    @DisplayName(".env.example 의 환경변수 이름(STORAGE_*)이 그대로 storage.* 에 바인딩된다")
    void bindsEnvironmentVariables() {
        runner.withInitializer(systemEnvironment(Map.of(
                "STORAGE_ENABLED", "true",
                "STORAGE_ENDPOINT", "http://127.0.0.1:9000",
                "STORAGE_REGION", "auto",
                "STORAGE_BUCKET", "test-bucket",
                "STORAGE_ACCESS_KEY", "test-access-key",
                "STORAGE_SECRET_KEY", "test-secret-key",
                "STORAGE_PUBLIC_BASE_URL", "https://cdn.example.test")))
            .withPropertyValues("storage.connect-timeout=2s", "storage.read-timeout=5s", "storage.api-call-timeout=8s")
            .run(context -> {
                assertThat(context).hasNotFailed();
                StorageProperties properties = context.getBean(StorageProperties.class);
                assertThat(properties.enabled()).isTrue();
                assertThat(properties.region()).isEqualTo("auto");
                assertThat(properties.accessKey()).isEqualTo("test-access-key");
                assertThat(properties.secretKey()).isEqualTo("test-secret-key");
                assertThat(properties.publicBaseUrl()).isEqualTo("https://cdn.example.test");
            });
    }

    @Test
    @DisplayName("타임아웃은 yml 기본값이지만 환경변수 STORAGE_API_CALL_TIMEOUT 으로도 바꿀 수 있다(relaxed binding)")
    void bindsApiCallTimeoutFromEnvironment() {
        runner.withInitializer(systemEnvironment(Map.of("STORAGE_API_CALL_TIMEOUT", "9s")))
            .withPropertyValues(enabledWithout("storage.api-call-timeout"))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(StorageProperties.class).apiCallTimeout()).isEqualTo(Duration.ofSeconds(9));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "storage.endpoint", "storage.region", "storage.bucket", "storage.access-key", "storage.secret-key", "storage.public-base-url"
    })
    @DisplayName("켜져 있는데 필수 값이 없으면 기동하지 않고 프로퍼티와 환경변수 이름을 알려준다")
    void failsWithoutRequiredValue(String property) {
        runner.withPropertyValues(enabledWithout(property))
            .run(context -> assertStartupFailure(context, property, environmentVariable(property)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "storage.endpoint", "storage.region", "storage.bucket", "storage.access-key", "storage.secret-key", "storage.public-base-url"
    })
    @DisplayName("켜져 있는데 ${...} 가 풀리지 않았거나 공백뿐이면 기동하지 않는다")
    void failsWithUnresolvedPlaceholderOrBlank(String property) {
        runner.withPropertyValues(enabled(property + "=${" + environmentVariable(property) + "}"))
            .run(context -> assertStartupFailure(context, property, "플레이스홀더"));
        runner.withInitializer(rawProperties(Map.of(property, "   ")))
            .withPropertyValues(enabledWithout(property))
            .run(context -> assertStartupFailure(context, property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"59s", "16m", "0s", "-5m"})
    @DisplayName("업로드 서명 TTL 이 1분 이상 15분 이하가 아니면 기동하지 않는다")
    void failsWithPresignTtlOutOfRange(String ttl) {
        runner.withPropertyValues(enabled("storage.presign-ttl=" + ttl))
            .run(context -> assertStartupFailure(context, "storage.presign-ttl", "15분"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1m", "15m", "PT10M"})
    @DisplayName("업로드 서명 TTL 경계값(1분 · 15분)은 기동한다")
    void startsWithPresignTtlOnBoundary(String ttl) {
        runner.withPropertyValues(enabled("storage.presign-ttl=" + ttl))
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"storage.connect-timeout", "storage.read-timeout", "storage.api-call-timeout"})
    @DisplayName("켜져 있는데 타임아웃이 없으면 기동하지 않는다 — 타임아웃 없는 블로킹 호출 금지")
    void failsWithoutTimeout(String property) {
        runner.withPropertyValues(enabledWithout(property))
            .run(context -> assertStartupFailure(context, property, "0 이하"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0s", "-1s"})
    @DisplayName("타임아웃이 0 이하면 기동하지 않는다")
    void failsWithNonPositiveTimeout(String timeout) {
        runner.withPropertyValues(enabled("storage.connect-timeout=" + timeout, "storage.read-timeout=" + timeout, "storage.api-call-timeout=" + timeout))
            .run(context -> assertStartupFailure(context, "storage.connect-timeout", "storage.read-timeout", "storage.api-call-timeout"));
    }

    @Test
    @DisplayName("호출 전체 시간 상한이 응답 타임아웃보다 짧으면 기동하지 않는다 — 같으면 기동한다")
    void failsWhenApiCallTimeoutShorterThanReadTimeout() {
        runner.withPropertyValues(enabled("storage.read-timeout=5s", "storage.api-call-timeout=4s"))
            .run(context -> assertStartupFailure(context, "storage.api-call-timeout", "storage.read-timeout", "이상"));
        runner.withPropertyValues(enabled("storage.read-timeout=5s", "storage.api-call-timeout=5s"))
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"storage.connect-timeout", "storage.read-timeout", "storage.api-call-timeout", "storage.presign-ttl"})
    @DisplayName("시간 형식이 틀리면 Boot 의 바인딩 오류 대신 위반 목록에 넣고, 메시지에 값을 넣지 않는다 — '없음' 위반을 겹쳐 알리지 않는다")
    void reportsMalformedDurationWithoutValue(String property) {
        runner.withPropertyValues(enabled(property + "=bad-duration-value"))
            .run(context -> {
                assertStartupFailure(context, property, "형식");
                assertThat(context.getStartupFailure().getMessage()).doesNotContain("bad-duration-value", "0 이하", "15분", "이상이어야");
                assertThat(context.getStartupFailure()).hasNoCause();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9000", "http://127.0.0.1:9000", "http://[::1]:9000", "https://storage.example.test"})
    @DisplayName("endpoint 는 https 이거나 루프백 http 다")
    void acceptsHttpsOrLoopbackEndpoint(String endpoint) {
        runner.withPropertyValues(enabled("storage.endpoint=" + endpoint))
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://storage.example.test", "ftp://storage.example.test", "storage.example.test", "https://", "https://storage.example.test?x=1",
        "https://storage.example.test#top", "https://user@storage.example.test", "https://exa mple.test"
    })
    @DisplayName("endpoint 가 루프백이 아닌 http · 상대 주소 · 쿼리 · 프래그먼트 · 사용자 정보 · 형식 오류면 기동하지 않는다")
    void failsWithInvalidEndpoint(String endpoint) {
        runner.withPropertyValues(enabled("storage.endpoint=" + endpoint))
            .run(context -> assertStartupFailure(context, "storage.endpoint", "STORAGE_ENDPOINT"));
    }

    @Test
    @DisplayName("공개 주소에 http(루프백 아님) · 쿼리를 쓰면 기동하지 않는다")
    void failsWithInvalidPublicBaseUrl() {
        runner.withPropertyValues(enabled("storage.public-base-url=http://cdn.example.test"))
            .run(context -> assertStartupFailure(context, "storage.public-base-url", "https"));
        runner.withPropertyValues(enabled("storage.public-base-url=https://cdn.example.test/?v=1"))
            .run(context -> assertStartupFailure(context, "storage.public-base-url", "쿼리"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Test-Bucket", "te", "test_bucket", "-test-bucket", "test-bucket-"})
    @DisplayName("버킷 이름이 S3 규칙에 맞지 않으면 기동하지 않는다")
    void failsWithInvalidBucketName(String bucket) {
        runner.withPropertyValues(enabled("storage.bucket=" + bucket))
            .run(context -> assertStartupFailure(context, "storage.bucket", "버킷 이름 규칙"));
    }

    @Test
    @DisplayName("어긋난 항목을 한 번에 모두 알려주고, 메시지에 설정값(키 · 주소)을 넣지 않는다")
    void reportsAllViolationsAtOnceWithoutValues() {
        runner.withPropertyValues("storage.enabled=true", "storage.secret-key=test-secret-value", "storage.endpoint=http://user:pass@storage.example.test",
                "storage.presign-ttl=30m")
            .run(context -> {
                assertStartupFailure(context, "storage.endpoint", "storage.region", "storage.bucket", "storage.access-key",
                    "storage.public-base-url", "storage.presign-ttl", "storage.connect-timeout", "storage.read-timeout", "storage.api-call-timeout");
                assertThat(context.getStartupFailure().getMessage()).doesNotContain("test-secret-value", "user:pass", "storage.example.test");
            });
    }

    // ── 공개 팩토리(ObjectStorageClients.create) — 가드를 거치지 않아도 같은 규칙 ──────────

    @Test
    @DisplayName("공개 팩토리도 가드와 같은 규칙으로 막는다 — 루프백이 아닌 http · TTL 범위 밖 · 타임아웃 없음. 메시지에 값을 넣지 않는다")
    void factoryRejectsWhatGuardRejects() {
        StorageProperties properties = new StorageProperties(true, "http://storage.example.test", "us-east-1", "test-bucket", "test-access-key",
            "test-secret-key", "https://cdn.example.test", true, Duration.ofMinutes(30), Duration.ofSeconds(2), Duration.ofSeconds(5), null);

        assertThatThrownBy(() -> ObjectStorageClients.create(properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll("storage.endpoint", "https", "storage.presign-ttl", "storage.api-call-timeout")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("storage.example.test", "test-access-key", "test-secret-key"));
    }

    @Test
    @DisplayName("공개 팩토리 — 호출 전체 상한이 응답 타임아웃보다 짧거나, 꺼져 있는데 채운 공개 주소가 https 가 아니면 막는다")
    void factoryRejectsTimeoutOrderAndDisabledPublicUrl() {
        StorageProperties shortCall = new StorageProperties(true, "https://storage.example.test", "us-east-1", "test-bucket", "test-access-key",
            "test-secret-key", "https://cdn.example.test", true, Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(4));
        StorageProperties disabledHttp = new StorageProperties(false, null, null, null, null, null, "http://cdn.example.test", null, null, null, null, null);

        assertThatThrownBy(() -> ObjectStorageClients.create(shortCall))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll("storage.api-call-timeout", "storage.read-timeout");
        assertThatThrownBy(() -> ObjectStorageClients.create(disabledHttp))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll("storage.public-base-url", "https");
    }

    @Test
    @DisplayName("공개 팩토리 — 맞는 설정이면 만든다(켜짐은 오프라인 서명, 꺼짐은 I/O 가 DISABLED)")
    void factoryCreatesWithValidSettings() throws Exception {
        StorageProperties enabled = new StorageProperties(true, "https://storage.example.test", "us-east-1", "test-bucket", "test-access-key",
            "test-secret-key", "https://cdn.example.test/", null, Duration.ofMinutes(5), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(8));
        StorageProperties disabled = new StorageProperties(null, null, null, null, null, null, null, null, null, null, null, null);

        ObjectStorageClient client = ObjectStorageClients.create(enabled);
        try {
            assertThat(client.presignPut("uploads/a.jpg", "image/jpeg", 10).uploadUrl()).startsWith("https://storage.example.test/test-bucket/uploads/a.jpg?");
        } finally {
            if (client instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
        assertDisabled(() -> ObjectStorageClients.create(disabled).head("uploads/a.jpg"));
    }

    private static void assertDisabled(ThrowingCallable call) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DISABLED));
    }

    private static void assertStartupFailure(AssertableApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContainingAll(messageParts);
    }

    private static Map<String, String> enabledValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("storage.enabled", "true");
        values.put("storage.endpoint", "http://localhost:9000");
        values.put("storage.region", "us-east-1");
        values.put("storage.bucket", "test-bucket");
        values.put("storage.access-key", "test-access-key");
        values.put("storage.secret-key", "test-secret-key");
        values.put("storage.public-base-url", "https://cdn.example.test/");
        values.put("storage.connect-timeout", "2s");
        values.put("storage.read-timeout", "5s");
        values.put("storage.api-call-timeout", "8s");
        return values;
    }

    /** 켜진 정상 설정에 {@code key=value} 를 덮어쓴다. */
    private static String[] enabled(String... overrides) {
        Map<String, String> values = enabledValues();
        for (String override : overrides) {
            String[] pair = override.split("=", 2);
            values.put(pair[0], pair[1]);
        }
        return toPairs(values);
    }

    private static String[] enabledWithout(String property) {
        Map<String, String> values = enabledValues();
        values.remove(property);
        return toPairs(values);
    }

    private static String[] toPairs(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
    }

    private static String environmentVariable(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** 개발 PC 의 실제 환경변수(STORAGE_*)가 결과를 바꾸지 않게 갈아 끼운다. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> systemEnvironment(Map<String, Object> variables) {
        String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        return context -> context.getEnvironment().getPropertySources().replace(name, new SystemEnvironmentPropertySource(name, variables));
    }

    /** {@code withPropertyValues} 는 값의 앞뒤 공백을 지운다. 공백뿐인 값은 이렇게 넣는다. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> rawProperties(Map<String, Object> values) {
        return context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-raw", values));
    }
}
