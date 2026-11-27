package com.commercefoundry.domainlayer.legaldraftgeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiRequest;
import com.commercefoundry.ai.client.AiStructuredResult;
import com.commercefoundry.domainlayer.job.application.service.processor.JobClaimProcessor;
import com.commercefoundry.domainlayer.job.application.service.processor.JobRunProcessor;
import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.job.domain.model.JobStatus;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.command.LegalDraftRegisterCommand;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.port.in.LegalDraftGenerationAdminUseCase;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftGenerationGate;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor.LegalDraftJobProcessor;
import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftPlan;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:legal-draft-generation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "operations.jobs.worker.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LegalDraftGenerationFlowTest extends ResourceServerSmokeTestSupport {
    private static final String PATH = "/api/v1/admin/store/legal-document-drafts/generations";
    private static final String PUBLIC = "/api/v1/store/legal-documents/PRIVACY";
    private static final long MEMBER = TestTokens.MEMBER_ID;
    private static final String FACT = "Example company";
    private static final List<String> SECTIONS = List.of("DATA_PURPOSES_AND_FIELDS", "RETENTION_AND_DELETION",
        "SHARING_PROCESSORS_AND_TRANSFERS", "RIGHTS_COOKIES_AND_SAFEGUARDS", "PRIVACY_CONTACT_AND_CHANGES");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST = """
        {"type":"PRIVACY","locale":"en","templateVersion":1,"confirmedFacts":{"COMPANY_NAME":"Example company",
        "RETENTION_AND_DELETION":"Example retention period"}}
        """;

    @MockitoBean AiClient client;
    @MockitoSpyBean LegalDraftGenerationGate gate;
    @Autowired LegalDraftGenerationAdminUseCase useCase;
    @Autowired LegalDraftJobProcessor worker;
    @Autowired JobClaimProcessor claims;
    @Autowired JobRunProcessor runs;
    @Autowired Environment environment;

    @Test
    void defaultOffAndActualJwtPermissions() throws Exception {
        assertThat(environment.getProperty("operations.legal-draft-generation.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("ai.features.legal-draft.prompt-version", Integer.class)).isEqualTo(1);
        assertThat(gate.enabled()).isFalse();
        assertThat(perform(register(key(), REQUEST)).getStatus()).isEqualTo(401);
        for (MemberRole role : List.of(MemberRole.CUSTOMER, MemberRole.MANAGER, MemberRole.STAFF)) {
            assertThat(perform(register(key(), REQUEST).header("Authorization", bearer(role))).getStatus()).isEqualTo(403);
            assertThat(perform(get(PATH + "/1").header("Authorization", bearer(role))).getStatus()).isEqualTo(403);
        }
        failure(admin(register(key(), REQUEST)), 503, "LEGAL_DRAFT_GENERATION_501");
        verifyNoInteractions(client);
    }

    @Test
    void successfulCandidateCanBeReviewedButRequiresSeparateManualSaveAndPublish() throws Exception {
        doReturn(true).when(gate).enabled();
        when(client.complete(any(AiRequest.class), org.mockito.ArgumentMatchers.eq(LegalDraftPlan.class)))
            .thenReturn(new AiStructuredResult<>(new LegalDraftPlan(SECTIONS.reversed()), "stub", 0, 0, "stop", "stub"));
        String requestKey = key();
        long id = Long.parseLong(data(admin(register(requestKey, REQUEST)), 202).get("jobId").stringValue());
        failure(admin(post(PATH + "/" + id + "/review")), 409, "LEGAL_DRAFT_GENERATION_004");
        var claimed = claims.claim(id, 0, LegalDraftJobProcessor.TYPE, "test", Duration.ofMinutes(5));
        assertThat(claimed.job().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.job().payloadJson()).isEqualTo("{}");
        var outcome = worker.execute(execution(id));
        assertThat(outcome.resultJson()).doesNotContain(FACT, "Example retention period");
        assertThat(runs.succeed(id, "test", 1, outcome.resultJson())).isTrue();
        var captured = ArgumentCaptor.forClass(AiRequest.class);
        verify(client).complete(captured.capture(), org.mockito.ArgumentMatchers.eq(LegalDraftPlan.class));
        assertThat(captured.getValue().prompt()).contains("RETENTION_AND_DELETION").doesNotContain(FACT, "Example retention period");
        assertThat(captured.getValue().callerRef()).isEqualTo("job:" + id + ":1");
        assertThat(captured.getValue().promptVersion()).isEqualTo(1);
        assertThat(data(admin(register(requestKey, REQUEST)), 202).get("jobId").stringValue()).isEqualTo(Long.toString(id));
        failure(admin(register(requestKey, REQUEST.replace(FACT, "Changed company"))), 409, "LEGAL_DRAFT_GENERATION_002");

        var result = data(admin(get(PATH + "/" + id)), 200);
        assertThat(result.get("status").get("code").stringValue()).isEqualTo("SUCCEEDED");
        var candidate = result.get("candidate");
        assertThat(candidate.get("body").stringValue()).contains(FACT, "Example retention period", "{{DATA_PURPOSES_AND_FIELDS}}");
        assertThat(candidate.get("unresolvedItems")).extracting(JsonNode::stringValue).contains("DATA_PURPOSES_AND_FIELDS")
            .doesNotContain("RETENTION_AND_DELETION");
        assertThat(candidate.get("templateId").stringValue()).isEqualTo("legal-privacy-en");
        assertThat(candidate.get("type").get("code").stringValue()).isEqualTo("PRIVACY");
        assertThat(candidate.get("type").get("name").stringValue()).isEqualTo("개인정보처리방침");
        assertThat(result.get("status").get("name").stringValue()).isEqualTo("성공");
        assertThat(result.get("reviewedAt").isNull()).isTrue();
        var other = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(MEMBER + 1, MemberRole.OWNER).tokenValue();
        for (var request : List.of(get(PATH + "/" + id), post(PATH + "/" + id + "/review"), post(PATH + "/" + id + "/cancel"))) {
            failure(perform(request.header("Authorization", other)), 404, "LEGAL_DRAFT_GENERATION_001");
        }
        var reviewed = data(admin(post(PATH + "/" + id + "/review")), 200).get("reviewedAt").stringValue();
        assertThat(data(admin(post(PATH + "/" + id + "/review")), 200).get("reviewedAt").stringValue()).isEqualTo(reviewed);
        assertThat(admin(get("/api/v1/admin/store/legal-documents/PRIVACY/en")).getStatus()).isEqualTo(404);
        assertThat(perform(get(PUBLIC).param("locale", "en")).getStatus()).isEqualTo(404);
    }

    @Test
    void invalidPromptInputOutputAndBudgetFailureNeverBecomeCandidates() throws Exception {
        doReturn(true).when(gate).enabled();
        failure(admin(register(key(), REQUEST.replace(FACT, "<script>bad</script>"))), 400, "LEGAL_DRAFT_GENERATION_100");
        failure(admin(register(key(), REQUEST.replace("COMPANY_NAME", "TOKEN"))), 400, "LEGAL_DRAFT_GENERATION_100");
        failure(admin(register(key(), REQUEST.replace("\"templateVersion\":1,", ""))), 400, "LEGAL_DRAFT_GENERATION_100");
        failure(admin(register(key(), REQUEST.replace("Version\":1", "Version\":2"))), 409, "LEGAL_DRAFT_GENERATION_003");
        verifyNoInteractions(client);
        when(client.complete(any(AiRequest.class), org.mockito.ArgumentMatchers.eq(LegalDraftPlan.class)))
            .thenThrow(new AiException(AiException.Reason.BUDGET_EXCEEDED, "legal-draft"))
            .thenReturn(new AiStructuredResult<>(new LegalDraftPlan(List.of("<b>untrusted</b>")), "stub", 0, 0, "stop", "stub"))
            .thenThrow(new AiException(AiException.Reason.UNAVAILABLE, "legal-draft"));
        for (String code : List.of("LEGAL_DRAFT_BUDGET_EXCEEDED", "LEGAL_DRAFT_AI_INVALID", "LEGAL_DRAFT_AI_FAILED")) {
            var id = registerDirect(key());
            claims.claim(id, 0, LegalDraftJobProcessor.TYPE, "test", Duration.ofMinutes(5));
            assertThatThrownBy(() -> worker.execute(execution(id))).isInstanceOfSatisfying(JobExecutionException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(code);
                assertThat(exception.isRetryable()).isFalse();
                assertThat(runs.fail(id, "test", 1, code, null, false)).isTrue();
            });
            var result = data(admin(get(PATH + "/" + id)), 200);
            assertThat(result.get("status").get("code").stringValue()).isEqualTo("FAILED");
            assertThat(result.get("candidate").isNull()).isTrue();
        }
    }

    @Test
    void sameKeyConcurrentRegistrationAndCancelledReplayReturnTheSameJob() throws Exception {
        doReturn(true).when(gate).enabled();
        var start = new CountDownLatch(1);
        String requestKey = key();
        Callable<Long> register = () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return registerDirect(requestKey);
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(register);
            var right = executor.submit(register);
            start.countDown();
            long id = left.get(15, TimeUnit.SECONDS);
            assertThat(right.get(15, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(data(admin(post(PATH + "/" + id + "/cancel")), 200).get("status").get("code").stringValue()).isEqualTo("CANCELLED");
            assertThat(registerDirect(requestKey)).isEqualTo(id);
            verifyNoInteractions(client);
        }
    }

    private long registerDirect(String requestKey) {
        return Long.parseLong(useCase.register(new LegalDraftRegisterCommand(MEMBER, requestKey, "PRIVACY", "en", 1, Map.of("COMPANY_NAME", FACT))).jobId());
    }

    private JobExecution execution(long jobId) {
        JobExecution execution = mock(JobExecution.class);
        when(execution.jobId()).thenReturn(jobId);
        when(execution.attempt()).thenReturn(1);
        return execution;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private MockHttpServletRequestBuilder register(String key, String request) {
        return post(PATH).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(request);
    }

    private MockHttpServletResponse admin(MockHttpServletRequestBuilder request) throws Exception {
        return perform(request.header("Authorization", bearer(MemberRole.ADMIN)));
    }

    private JsonNode data(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return JSON.readTree(response.getContentAsByteArray()).get("dataBody");
    }

    private void failure(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentAsString()).contains(code);
    }
}
