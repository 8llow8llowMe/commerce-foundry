package com.commercefoundry.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 각 소비 모듈의 테스트 클래스패스에서 Docker/CI 조건 누락을 검사한다. */
public abstract class TestcontainersConditionTestSupport {

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
