package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.commercefoundry.domainlayer.member.domain.model.LoginGuardPolicy;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import com.commercefoundry.support.TestLoginGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 실제 Compose의 보간 결과를 로그인 제한 정책 빈까지 연결한다 (#32).
 *
 * <p>컨테이너·Docker 데몬·Vault에 접근하지 않고 Compose CLI만 사용한다. CLI 없는 local은 건너뛰고 CI에서는 누락 시 실패한다.
 * 독립 실행 파일을 쓰는 local은 {@code TEST_DOCKER_COMPOSE_BINARY}에 경로를 지정한다(테스트 전용).
 */
class AuthDeploymentLoginGuardTest {

    private static final Path COMPOSE_FILE = Path.of("docker-compose-auth-service.yml").toAbsolutePath();
    private static final String ENABLED = "AUTH_LOGIN_GUARD_ENABLED";
    private static final String HASH_KEY = "AUTH_LOGIN_GUARD_HASH_KEY";
    private static final Pattern COMPOSE_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");
    private static List<String> compose;
    private static boolean composeAvailable;

    @TempDir
    Path directory;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthLoginGuardProperties.class)
    static class GuardConfiguration {
    }

    @BeforeAll
    static void requireComposeCli() throws IOException, InterruptedException {
        String binary = System.getenv("TEST_DOCKER_COMPOSE_BINARY");
        boolean explicit = binary != null && !binary.isBlank();
        compose = explicit ? List.of(binary) : List.of("docker", "compose");
        List<String> version = new ArrayList<>(compose);
        version.add("version");
        try {
            Process process = new ProcessBuilder(version).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            waitFor(process);
            if (process.exitValue() != 0 && !explicit && !Boolean.parseBoolean(System.getenv("CI"))) {
                return;
            }
            assertThat(process.exitValue()).as("Compose CLI version 조회").isZero();
            composeAvailable = true;
        } catch (IOException unavailable) {
            if (explicit || Boolean.parseBoolean(System.getenv("CI"))) {
                throw new AssertionError("Compose CLI가 필요하다. CI에서는 누락을 건너뛰지 않는다.", unavailable);
            }
            // 파라미터 케이스를 발견한 뒤 건너뛴다. BeforeAll에서 abort하면 표적 Gradle 실행이 'No tests found'로 실패한다.
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("scenarios")
    @DisplayName("배포 스위치의 미설정·명시값·빈 값과 HMAC 키 유무가 실제 로그인 제한 정책에 반영된다")
    void composeEnvironmentBindsToLoginGuard(String profile, Scenario scenario) throws IOException, InterruptedException {
        assumeTrue(composeAvailable, "Compose CLI 없음 — CI에서 실제 배포 설정을 검증한다");
        Map<String, Object> environment = renderEnvironment(profile, scenario);
        assertThat(environment.get("SPRING_PROFILES_ACTIVE")).isEqualTo(profile);
        assertThat(environment.get(ENABLED)).isEqualTo(scenario.rendered());
        List<PropertySource<?>> application = applicationSources(profile);

        new ApplicationContextRunner().withUserConfiguration(GuardConfiguration.class, AuthLoginGuardBeansConfig.class)
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                sources.addFirst(new MapPropertySource("compose-test", environment));
                application.forEach(sources::addLast);
            }).run(context -> {
                if (scenario.policy() == null) {
                    assertThat(context).hasFailed().getFailure().hasMessageContaining("auth.login-guard");
                } else {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LoginGuardPolicy.class).enabled()).isEqualTo(scenario.policy());
                }
            });
    }

    private Map<String, Object> renderEnvironment(String profile, Scenario scenario) throws IOException, InterruptedException {
        // Compose config 전용 가상 값이다. 키의 암호학적 유효성은 이 명령에서 검사하지 않으며 앱은 가드 빈만 띄운다.
        Map<String, String> values = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("EUREKA_HOST", "discovery-fixture"), Map.entry("EUREKA_PASSWORD", "test-only"),
            Map.entry("DB_URL", "jdbc:postgresql://db-fixture/test"), Map.entry("DB_USERNAME", "test-only"), Map.entry("DB_PASSWORD", "test-only"),
            Map.entry("REDIS_HOST", "redis-fixture"), Map.entry("REDIS_PORT", "6379"), Map.entry("REDIS_PASSWORD", "test-only"),
            Map.entry("SNOWFLAKE_DATACENTER_ID", "0"), Map.entry("JWT_PUBLIC_KEY", "test-only-not-a-key"), Map.entry("JWT_PRIVATE_KEY", "test-only-not-a-key"),
            Map.entry("AUTH_OTP_SECRET_KEY", "test-only-not-a-key"), Map.entry("INTERNAL_JWT_SECRET", "test-only-not-a-key"),
            Map.entry("AUTH_SERVICE_PORT_DEV", "18081"), Map.entry("AUTH_SERVICE_PORT_PROD", "28081"), Map.entry("COMMERCE_DOCKER_NETWORK", "test-only-network")
        ));
        if (scenario.input() != null) {
            values.put(ENABLED, scenario.input());
        }
        if (scenario.key()) {
            values.put(HASH_KEY, TestLoginGuard.HASH_KEY);
        }
        Path envFile = directory.resolve("fixture.env");
        StringBuilder env = new StringBuilder();
        values.forEach((key, value) -> env.append(key).append('=').append(value).append('\n'));
        Files.writeString(envFile, env);

        List<String> command = new ArrayList<>(compose);
        command.addAll(List.of("-p", "auth-deployment-test", "--env-file", envFile.toString(), "-f", COMPOSE_FILE.toString(), "config", "--format", "json"));
        Path output = directory.resolve("config.json");
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(directory.resolve("stderr.txt").toFile());
        // 실값이 있더라도 가상 env 파일보다 우선되지 않도록 모든 참조 변수를 자식 프로세스 환경에서 제거한다.
        COMPOSE_VARIABLE.matcher(Files.readString(COMPOSE_FILE)).results().forEach(match -> builder.environment().remove(match.group(1)));
        builder.environment().keySet().removeIf(key -> key.startsWith("COMPOSE_"));
        Process process = builder.start();
        waitFor(process);
        assertThat(process.exitValue()).as("%s의 Compose config 해석", profile).isZero();

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<?, ?> root = new Yaml(new SafeConstructor(options)).load(Files.readString(output));
        Map<?, ?> services = (Map<?, ?>) root.get("services");
        Map<?, ?> service = (Map<?, ?>) services.get("auth-service-" + profile);
        Map<?, ?> rendered = (Map<?, ?>) service.get("environment");
        Map<String, Object> environment = new LinkedHashMap<>();
        rendered.forEach((key, value) -> environment.put(String.valueOf(key), value));
        return environment;
    }

    private List<PropertySource<?>> applicationSources(String profile) throws IOException {
        List<PropertySource<?>> sources = new ArrayList<>();
        for (String file : List.of("application-" + profile + ".yml", "application.yml")) {
            ClassPathResource resource = new ClassPathResource(file);
            if (resource.exists()) {
                sources.addAll(new YamlPropertySourceLoader().load(file, resource));
            }
        }
        return sources;
    }

    private static void waitFor(Process process) throws InterruptedException {
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Compose CLI가 30초 안에 종료되지 않았다");
        }
    }

    static Stream<Arguments> scenarios() {
        List<Scenario> cases = List.of(
            new Scenario("배포 기본 꺼짐", null, true, "false", false),
            new Scenario("명시적 켬", "true", true, "true", true),
            new Scenario("키 없이 명시적 끔", "false", false, "false", false),
            new Scenario("빈 값은 기동 거절", "", true, "", null),
            new Scenario("잘못된 값은 기동 거절", "invalid", true, "invalid", null),
            new Scenario("키 없는 켬은 기동 거절", "true", false, "true", null)
        );
        return Stream.of("dev", "prod").flatMap(profile -> cases.stream().map(scenario -> Arguments.of(profile, scenario)));
    }

    record Scenario(String name, String input, boolean key, String rendered, Boolean policy) {
        @Override
        public String toString() {
            return name;
        }
    }
}
