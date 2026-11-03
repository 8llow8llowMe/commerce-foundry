package com.commercefoundry.domainlayer.adminaudit.application.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.domainlayer.product.application.model.StockAlertAuditAction;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨트롤러의 {@code @AdminAudited.action()} 집합과 {@link ProductAdminAuditAction} 값 집합이 같은지 본다 (#163, {@code security-guide.md} §11-4).
 * 선언만 하고 enum 에 없는 행위(줄을 쓰지 않는 선언)나, enum 에만 있고 아무 핸들러도 선언하지 않은 행위가 생기면 실패다.
 * 선언이 빠진 쓰기 핸들러 자체는 {@code ProductServiceSecuritySmokeTest} 의 공통 가드가 잡는다.
 */
class ProductAdminAuditDeclarationTest {

    private static final String BASE_PACKAGE = "com.commercefoundry.domainlayer";

    @Test
    @DisplayName("product 컨트롤러가 선언한 감사 행위 집합 == ProductAdminAuditAction 값 집합이고, 한 행위를 두 핸들러가 겹쳐 쓰지 않는다")
    void declaredActionsMatchEnum() {
        List<String> declared = declaredActions();
        Set<String> enumValues = Arrays.stream(ProductAdminAuditAction.values()).map(Enum::name).collect(Collectors.toSet());
        enumValues.addAll(Arrays.stream(StockAlertAuditAction.values()).map(Enum::name).collect(Collectors.toSet()));
        enumValues.addAll(Arrays.stream(SkuAdminAuditAction.values()).map(Enum::name).collect(Collectors.toSet()));

        assertThat(declared).as("선언 수집이 비어 있으면 스캔이 잘못된 것이다").isNotEmpty();
        assertThat(declared).as("한 행위 이름은 한 핸들러만 선언한다").doesNotHaveDuplicates();
        assertThat(Set.copyOf(declared)).isEqualTo(enumValues);
    }

    private static List<String> declaredActions() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> actions = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> controller = ClassUtils.resolveClassName(candidate.getBeanClassName(), ProductAdminAuditDeclarationTest.class.getClassLoader());
            for (Method method : controller.getDeclaredMethods()) {
                AdminAudited audited = method.getAnnotation(AdminAudited.class);
                if (audited != null) {
                    actions.add(audited.action());
                }
            }
        }
        return actions;
    }
}
