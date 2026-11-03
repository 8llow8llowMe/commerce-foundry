package com.commercefoundry.security.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.adminaudit.application.model.SkuAdminAuditAction;
import com.commercefoundry.domainlayer.product.adapter.in.admin.controller.SkuAdminController;
import com.commercefoundry.security.audit.AdminAudited;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/** 실제 신규 컨트롤러 선언이 security-core 공통 가드를 통과하고 누락이 검출되는지 검증한다. */
class SkuAdminAuditDeclarationTest {
    @Test
    void skuWriteDeclaresKnownActionAndPassesCommonGuard() throws Exception {
        var method = java.util.Arrays.stream(SkuAdminController.class.getDeclaredMethods()).filter(m -> m.getName().equals("replace")).findFirst().orElseThrow();
        assertThat(method.getAnnotation(AdminAudited.class).action()).isEqualTo(SkuAdminAuditAction.PRODUCT_SKUS_REPLACE.name());
        var mapping = RequestMappingInfo.paths("/api/v1/admin/products/{productId}/skus")
            .methods(org.springframework.web.bind.annotation.RequestMethod.PUT).build();
        var handler = new HandlerMethod(new SkuAdminController(null), method);
        assertThat(ResourceServerSmokeTestSupport.scanAuditDeclarations(Map.of(mapping, handler), Map.of()).violations()).isEmpty();
        var missing = new HandlerMethod(new MissingAudit(), MissingAudit.class.getDeclaredMethod("replace"));
        assertThat(ResourceServerSmokeTestSupport.scanAuditDeclarations(Map.of(mapping, missing), Map.of()).violations()).hasSize(1);
    }

    static class MissingAudit {
        public void replace() {
        }
    }
}
