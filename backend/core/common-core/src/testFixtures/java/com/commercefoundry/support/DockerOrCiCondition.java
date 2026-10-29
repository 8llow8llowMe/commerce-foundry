package com.commercefoundry.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * {@link EnabledWithDockerOrOnCi} 의 판정. 환경을 읽는 부분과 판정({@link #evaluate})을 나눠 판정만 단위 테스트한다.
 */
public class DockerOrCiCondition implements ExecutionCondition {

    /** GitHub Actions 가 모든 단계에 {@code CI=true} 로 넣는다. */
    static final String CI_ENV = "CI";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return evaluate(DockerClientFactory.instance().isDockerAvailable(), System.getenv(CI_ENV));
    }

    /**
     * @param dockerAvailable Docker 데몬에 붙을 수 있는가
     * @param ciEnv 환경변수 {@code CI} 값. 없으면 null
     */
    static ConditionEvaluationResult evaluate(boolean dockerAvailable, String ciEnv) {
        if (dockerAvailable) {
            return ConditionEvaluationResult.enabled("Docker 있음");
        }
        if (Boolean.parseBoolean(ciEnv)) {
            return ConditionEvaluationResult.enabled("CI=true — Docker 가 없으면 컨테이너 기동 실패로 테스트가 실패한다");
        }
        return ConditionEvaluationResult.disabled("Docker 없음 — CI 에서 실행된다");
    }
}
