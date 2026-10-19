package com.commercefoundry.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.net.URL;
import java.security.CodeSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link HexagonalArchitectureRules} 의 import 캐시. 인터페이스는 private 상태를 갖지 못해 따로 둔다.
 * security-core 의 {@code ControllerAuthorizationArchTestSupport} 와 같은 방식(애플리케이션 클래스의 코드 소스 하나)을 일부러 중복한다 —
 * 그 캐시를 나눠 쓰면 common-core 픽스처가 security-core 픽스처에 의존하게 된다.
 */
final class HexagonalArchitectureImports {

    /** 서비스 테스트 클래스마다 한 번만 import 한다 — 규칙 테스트가 같은 결과를 나눠 쓴다. */
    private static final Map<Class<?>, JavaClasses> IMPORTED = new ConcurrentHashMap<>();

    private HexagonalArchitectureImports() {
    }

    static JavaClasses of(Class<?> applicationClass) {
        return IMPORTED.computeIfAbsent(applicationClass, HexagonalArchitectureImports::importCodeSourceOf);
    }

    /** 클래스가 로드된 코드 소스(Gradle 테스트에서는 {@code build/classes/java/main} 디렉터리, jar 면 그 jar) 하나만 import 한다. */
    private static JavaClasses importCodeSourceOf(Class<?> type) {
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        assertThat(codeSource).as("%s 의 코드 소스", type.getName()).isNotNull();
        URL location = codeSource.getLocation();
        return new ClassFileImporter().importUrl(location);
    }
}
