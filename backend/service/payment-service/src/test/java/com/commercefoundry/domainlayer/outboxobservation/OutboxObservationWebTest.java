package com.commercefoundry.domainlayer.outboxobservation;

import static org.assertj.core.api.Assertions.assertThat;
import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercefoundry.domainlayer.outboxobservation.application.port.out.OutboxObservationRepositoryPort;
import com.commercefoundry.domainlayer.outboxobservation.application.port.out.query.OutboxObservationQueryResult;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.support.TestServiceTokens;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OutboxObservationWebTest {

    static final String PATH = "/internal/v1/outbox-observations/ready";
    @Autowired MockMvc mvc;
    @MockitoBean OutboxObservationRepositoryPort repository;

    @Test
    void operationsReceivesExactlyThreeFieldsAndNullWithNoStoreWhileGatesAreClosed() throws Exception {
        when(repository.observeReady(any())).thenReturn(new OutboxObservationQueryResult(0, null));
        var response = mvc.perform(get(PATH).header("Authorization", bearer("operations-service", "payment-service")))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body = JsonMapper.builder().build().readTree(response.getContentAsString()).get("dataBody");
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        assertThat(names).containsExactlyInAnyOrder("observedAt", "readyUnpublishedCount", "oldestReadyAvailableAt");
        assertThat(Instant.parse(body.get("observedAt").asText()).getNano() % 1_000).isZero();
        assertThat(body.get("readyUnpublishedCount").asLong()).isZero();
        assertThat(body.get("oldestReadyAvailableAt").isNull()).isTrue();
    }

    @Test
    void readyFactsPreserveCountAndOldestTimestamp() throws Exception {
        Instant oldest = Instant.parse("2026-11-03T01:02:03.123456Z");
        when(repository.observeReady(any())).thenReturn(new OutboxObservationQueryResult(7, oldest));
        var response = mvc.perform(get(PATH).header("Authorization", bearer("operations-service", "payment-service")))
            .andExpect(status().isOk()).andReturn().getResponse();
        var body = JsonMapper.builder().build().readTree(response.getContentAsString()).get("dataBody");
        assertThat(body.get("readyUnpublishedCount").asLong()).isEqualTo(7);
        assertThat(Instant.parse(body.get("oldestReadyAvailableAt").asText())).isEqualTo(oldest);
    }

    @Test
    void realSecurityChainRejectsOtherServicesAndMissingInvalidWrongAudienceOrUserTokens() throws Exception {
        assertEnvelope(mvc.perform(get(PATH).header("Authorization", bearer("order-service", "payment-service")))
            .andReturn().getResponse(), SecurityErrorCode.FORBIDDEN);
        assertEnvelope(mvc.perform(get(PATH)).andReturn().getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertEnvelope(mvc.perform(get(PATH).header("Authorization", "Bearer malformed"))
            .andReturn().getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertEnvelope(mvc.perform(get(PATH).header("Authorization", bearer("operations-service", "order-service")))
            .andReturn().getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        for (MemberRole role : MemberRole.values()) {
            String user = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
            assertEnvelope(mvc.perform(get(PATH).header("Authorization", "Bearer " + user))
                .andReturn().getResponse(), SecurityErrorCode.SERVICE_UNAUTHORIZED);
        }
    }

    @Test
    void dataFailureReturnsSafe503WithoutOriginalCauseInResponseOrLogs(CapturedOutput output) throws Exception {
        String sentinel = "sentinel-private-payload-key-order-SQL";
        when(repository.observeReady(any())).thenThrow(new DataAccessResourceFailureException(sentinel));
        var response = mvc.perform(get(PATH).header("Authorization", bearer("operations-service", "payment-service")))
            .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("OUTBOX_OBSERVATION_001").doesNotContain(sentinel);
        assertThat(output.getAll()).contains("OUTBOX_OBSERVATION_001", "DataAccessResourceFailureException").doesNotContain(sentinel);
    }

    private static String bearer(String caller, String audience) {
        return TestServiceTokens.bearer(caller, audience);
    }
}
