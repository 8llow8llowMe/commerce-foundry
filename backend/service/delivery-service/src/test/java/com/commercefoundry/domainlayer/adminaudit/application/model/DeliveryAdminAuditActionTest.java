package com.commercefoundry.domainlayer.adminaudit.application.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.audit.AdminAudited;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨트롤러의 {@code @AdminAudited(action)} 집합과 {@link DeliveryAdminAuditAction} 값 집합이 같은지 본다 (#163, {@code security-guide.md} §11-4) —
 * 선언만 하고 행위 enum 을 빠뜨리거나, 쓰지 않는 행위가 enum 에 남지 않게. 선언이 Facade 의 감사 줄과 맞는지는 Facade 테스트가 본다.
 */
class DeliveryAdminAuditActionTest {

    @Test
    @DisplayName("@AdminAudited 행위 집합 == DeliveryAdminAuditAction 값 집합")
    void declaredActionsMatchEnum() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> declared = new HashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.commercefoundry.domainlayer")) {
            for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
                AdminAudited audited = method.getAnnotation(AdminAudited.class);
                if (audited != null) {
                    declared.add(audited.action());
                }
            }
        }

        assertThat(declared).isEqualTo(Arrays.stream(DeliveryAdminAuditAction.values()).map(Enum::name).collect(Collectors.toSet()));
    }
}
