package com.commercefoundry.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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

}
