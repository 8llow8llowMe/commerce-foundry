package com.commercefoundry.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 애플리케이션의 환경변수 계약을 배포 Compose와 대조한다 (#32).
 *
 * <p>#135에서 환경변수 전달 누락과 병렬 머지의 YAML 중복 키가 Java 검사만으로 발견되지 않았다.
 * 실제 배포 파일을 읽되 Vault·Docker·서버에 접근하지 않는다. Compose 파일은 test 태스크 입력으로 선언한다.
 */
class GatewayDeploymentEnvironmentTest {

    private static final Path COMPOSE_FILE = Path.of("docker-compose-api-gateway.yml");
    private static final Pattern APPLICATION_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("dev·prod 배포는 중복 키 없이 앱의 환경변수를 같은 이름으로 전달하고 필수 값의 빈 문자열도 거절한다")
    void deployedContainersForwardApplicationVariables(String profile) throws IOException {
        Map<?, ?> environment = deploymentEnvironment(profile);
        Map<String, Boolean> variables = applicationVariables(profile);
        assertThat(variables).as("앱 환경변수 추출이 비어 있으면 검증을 생략할 수 없다").isNotEmpty();

        Set<String> allowed = new LinkedHashSet<>(variables.keySet());
        // JVM의 시간대와 Compose에서 고정하는 프로파일은 application.yml 플레이스홀더 밖의 계약이다.
        allowed.addAll(Set.of("TIME_ZONE", "SPRING_PROFILES_ACTIVE"));
        assertThat(environment.keySet().stream().map(Object::toString).toList())
            .as("%s 컨테이너에 필요한 키만 전달한다", profile).containsExactlyInAnyOrderElementsOf(allowed);
        assertThat(environment.get("SPRING_PROFILES_ACTIVE")).isEqualTo(profile);

        for (Map.Entry<String, Boolean> variable : variables.entrySet()) {
            String name = variable.getKey();
            Object expression = environment.get(name);
            // 잘못된 설정에 리터럴 시크릿이 들어 있어도 assertion에 값 원문을 출력하지 않는다.
            boolean sameVariable = expression instanceof String text && text.matches("\\$\\{" + name + "(?::?[-?][^}]*)?}");
            assertThat(sameVariable).as("%s 컨테이너의 %s는 같은 이름의 배포 값을 전달해야 한다", profile, name).isTrue();
            if (variable.getValue()) {
                assertThat(((String) expression).startsWith("${" + name + ":?"))
                    .as("필수 키 %s는 미설정·빈 값을 Compose에서 거절해야 한다", name).isTrue();
            }
        }
    }

    private Map<?, ?> deploymentEnvironment(String profile) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(COMPOSE_FILE)) {
            Map<?, ?> root = yaml.load(reader);
            Map<?, ?> services = (Map<?, ?>) root.get("services");
            Map<?, ?> service = (Map<?, ?>) services.get("api-gateway-" + profile);
            return (Map<?, ?>) service.get("environment");
        }
    }

    private Map<String, Boolean> applicationVariables(String profile) throws IOException {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String file : List.of("application.yml", "application-" + profile + ".yml")) {
            ClassPathResource resource = new ClassPathResource(file);
            if (!resource.exists()) {
                // 현재 prod는 별도 파일 없이 공통 설정을 쓴다.
                continue;
            }
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(file, resource)) {
                EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
                for (String property : enumerable.getPropertyNames()) {
                    properties.put(property, enumerable.getProperty(property));
                }
            }
        }

        Map<String, Boolean> variables = new LinkedHashMap<>();
        for (Object value : properties.values()) {
            Matcher matcher = APPLICATION_VARIABLE.matcher(String.valueOf(value));
            while (matcher.find()) {
                // 여러 설정에서 같은 변수를 쓰면 기본값 없는 참조가 하나라도 있는지 확인한다.
                variables.merge(matcher.group(1), matcher.group(2) == null, Boolean::logicalOr);
            }
        }
        return variables;
    }
}
