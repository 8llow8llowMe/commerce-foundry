package com.commercefoundry.domainlayer.member.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.security.audit.AdminAuditExempt;
import com.commercefoundry.security.audit.AdminAudited;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

class AdminOtpAuditDeclarationTest {

    @Test
    void existingOtpWriteMappingsDeclareTheirActualActionsAndStartIsExempt() {
        Map<String, String> actions = Map.of("confirmEnrollment", "ADMIN_OTP_ENROLL", "disable", "ADMIN_OTP_DISABLE",
            "regenerateRecoveryCodes", "ADMIN_OTP_RECOVERY_REGENERATE", "recover", "ADMIN_OTP_PEER_RECOVERY");
        var handlers = java.util.stream.Stream.of(AdminOtpAdminController.class, AdminOtpPeerRecoveryAdminController.class)
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods())).filter(method -> method.isAnnotationPresent(PostMapping.class)).toList();
        assertThat(handlers).hasSize(5);
        for (Method handler : handlers) {
            if (handler.getName().equals("startEnrollment")) {
                assertThat(handler.getAnnotation(AdminAuditExempt.class).reason()).contains("확인 전", "TTL");
                assertThat(handler.getAnnotation(AdminAudited.class)).isNull();
            } else {
                assertThat(handler.getAnnotation(AdminAudited.class).action()).isEqualTo(actions.get(handler.getName()));
                assertThat(handler.getAnnotation(AdminAuditExempt.class)).isNull();
            }
        }
    }
}
