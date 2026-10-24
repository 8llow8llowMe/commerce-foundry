package com.commercefoundry.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.junit.jupiter.Testcontainers;

class DockerOrCiConditionTest {

    @ParameterizedTest(name = "CI={0}")
    @NullSource
    @ValueSource(strings = {"true", "false", ""})
    @DisplayName("Docker 가 있으면 CI 여부와 무관하게 돈다")
    void enabledWithDocker(String ciEnv) {
        ConditionEvaluationResult result = DockerOrCiCondition.evaluate(true, ciEnv);

        assertThat(result.isDisabled()).isFalse();
    }

    @ParameterizedTest(name = "CI={0}")
    @ValueSource(strings = {"true", "TRUE"})
    @DisplayName("Docker 가 없어도 CI 면 돈다 — 컨테이너 기동 실패로 시끄럽게 실패하게")
    void enabledOnCiWithoutDocker(String ciEnv) {
        ConditionEvaluationResult result = DockerOrCiCondition.evaluate(false, ciEnv);

        assertThat(result.isDisabled()).isFalse();
        assertThat(result.getReason()).hasValueSatisfying(reason -> assertThat(reason).contains("CI"));
    }

    @ParameterizedTest(name = "CI={0}")
    @NullSource
    @ValueSource(strings = {"false", "", "yes"})
    @DisplayName("Docker 없는 로컬은 건너뛰고, 사유에 CI 에서 실행된다고 적는다")
    void disabledLocallyWithoutDocker(String ciEnv) {
        ConditionEvaluationResult result = DockerOrCiCondition.evaluate(false, ciEnv);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).hasValue("Docker 없음 — CI 에서 실행된다");
    }

    @Test
    @DisplayName("@Testcontainers 테스트는 전부 disabledWithoutDocker 없이 @EnabledWithDockerOrOnCi 를 쓴다 — CI 에서 조용히 skip 되지 않게")
    void testcontainersTestsUseCondition() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Testcontainers.class));
        List<String> classNames = scanner.findCandidateComponents("com.commercefoundry").stream()
            .map(BeanDefinition::getBeanClassName)
            .toList();

        assertThat(classNames).as("@Testcontainers 테스트").isNotEmpty();
        for (String className : classNames) {
            // 초기화하지 않는다 — static 컨테이너 필드를 만들지 않고 애노테이션만 읽는다
            Class<?> testClass = Class.forName(className, false, getClass().getClassLoader());
            assertThat(testClass.getAnnotation(Testcontainers.class).disabledWithoutDocker()).as("%s 의 disabledWithoutDocker", className).isFalse();
            assertThat(testClass.isAnnotationPresent(EnabledWithDockerOrOnCi.class)).as("%s 의 @EnabledWithDockerOrOnCi", className).isTrue();
        }
    }
}
