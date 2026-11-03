package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.onboarding.adapter.out.persistence.repository.OnboardingTourProgressRepository;
import com.commercefoundry.domainlayer.onboarding.application.service.processor.OnboardingTaskProcessor;
import com.commercefoundry.global.properties.OnboardingProperties;
import com.commercefoundry.global.properties.OnboardingProperties.Guide;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 배포 yml → 인증된 HTTP → 실제 H2 진행 저장소. 업무 사실의 원격 조회만 대체한다(#544). */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:onboarding-configured-guides;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;"
        + "INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.jobs.worker.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OnboardingConfiguredGuidesTest {

    private static final String PATH = "/api/v1/admin/onboarding/me";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Guide> GUIDES = List.of(
        new Guide("console", 1), new Guide("stocks", 1), new Guide("stock-detail", 1), new Guide("size-charts", 1),
        new Guide("size-chart-editor", 1), new Guide("size-chart-templates", 1), new Guide("shipping-domestic", 1),
        new Guide("product-new", 2), new Guide("product-edit", 1), new Guide("payments", 1), new Guide("orders", 2), new Guide("order-detail", 2)
    );

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private OnboardingProperties properties;

    @Autowired
    private OnboardingTourProgressRepository repository;

    @MockitoBean
    private OnboardingTaskProcessor tasks;

    @BeforeEach
    void clearProgress() {
        repository.deleteAllInBatch();
        given(tasks.getMine(anyLong())).willReturn(List.of());
    }

    @Test
    @DisplayName("기본 yml 바인딩과 조회 순서가 맞고 기존 7개 가이드의 저장 기록을 보존한다")
    void bindsDeployedGuidesAndPreservesExistingProgressAndOrder() throws Exception {
        assertThat(properties.guides()).containsExactlyElementsOf(GUIDES);
        for (Guide guide : GUIDES.subList(0, 7)) {
            body(put(guide.key(), guide.version()));
        }

        JsonNode tours = body(mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()).get("tours");
        assertThat(tours.size()).isEqualTo(GUIDES.size());
        for (int index = 0; index < GUIDES.size(); index++) {
            JsonNode tour = tours.get(index);
            assertThat(tour.get("guideKey").asString()).isEqualTo(GUIDES.get(index).key());
            assertThat(tour.get("guideVersion").asInt()).isEqualTo(GUIDES.get(index).version());
            assertThat(tour.get("status").get("code").asString()).isEqualTo(index < 7 ? "IN_PROGRESS" : "NOT_STARTED");
        }
        assertThat(repository.count()).isEqualTo(7L);
    }

    @ParameterizedTest
    @CsvSource({"product-new,2", "product-edit,1", "payments,1", "orders,2", "order-detail,2"})
    @DisplayName("배포된 5개 투어는 PUT 200이고 재요청 뒤에도 한 행으로 저장돼 GET에서 진행을 조회한다")
    void deployedTourProgressIsStoredAndReturned(String key, int version) throws Exception {
        JsonNode saved = body(put(key, version));
        assertThat(saved.get("guideKey").asString()).isEqualTo(key);
        assertThat(saved.get("guideVersion").asInt()).isEqualTo(version);
        body(put(key, version));

        JsonNode tours = body(mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer()).exchange()).get("tours");
        int index = GUIDES.indexOf(new Guide(key, version));
        JsonNode restored = tours.get(index);
        assertThat(restored.get("guideKey").asString()).isEqualTo(key);
        assertThat(restored.get("guideVersion").asInt()).isEqualTo(version);
        assertThat(restored.get("status").get("code").asString()).isEqualTo("IN_PROGRESS");
        assertThat(restored.get("currentStep").asInt()).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(1L);
    }

    private MvcTestResult put(String key, int version) {
        return mvc.put().uri(PATH + "/tours/" + key).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"guideVersion": %d, "status": "IN_PROGRESS", "currentStep": 2}
                """.formatted(version)).exchange();
    }

    private JsonNode body(MvcTestResult response) throws Exception {
        assertThat(response).hasStatusOk();
        return JSON.readTree(response.getResponse().getContentAsString()).get("dataBody");
    }

    private String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();
    }
}
