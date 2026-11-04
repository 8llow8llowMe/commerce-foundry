package com.commercefoundry.domainlayer.paymentobservation;

import static org.assertj.core.api.Assertions.assertThat;
import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercefoundry.domainlayer.payment.application.port.out.PaymentAnomalyObservationPort;
import com.commercefoundry.domainlayer.payment.domain.model.PaymentAnomalyObservation;
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
class PaymentObservationWebTest {

    static final String PATH = "/internal/v1/payment-observations/anomalies";
    @Autowired MockMvc mvc;
    @MockitoBean PaymentAnomalyObservationPort repository;

    @Test
    void operationsReceivesOnlySixBucketsAndNullWithNoStoreWhileGatesAreClosed() throws Exception {
        when(repository.observeAnomalies(any())).thenReturn(facts(0, null));
        var response = mvc.perform(get(PATH).header("Authorization", bearer("operations-service", "payment-service")))
            .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body = JsonMapper.builder().build().readTree(response.getContentAsString()).get("dataBody");
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        assertThat(names).containsExactlyInAnyOrder("observedAt", "unknown", "manualReviewUnknown", "inProgress", "returnedReady", "lateApproved", "doneOrderUnsynced");
        assertThat(Instant.parse(body.get("observedAt").asText()).getNano() % 1_000).isZero();
        for (String lane : List.of("unknown", "manualReviewUnknown", "inProgress", "returnedReady", "lateApproved", "doneOrderUnsynced")) {
            List<String> fields = new ArrayList<>();
            body.get(lane).propertyNames().forEach(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("count", "oldestAt");
            assertThat(body.get(lane).get("count").asLong()).isZero();
            assertThat(body.get(lane).get("oldestAt").isNull()).isTrue();
        }
    }

    @Test
    void sixLanesPreserveDistinctCountsAndOldestTimestamps() throws Exception {
        Instant oldest = Instant.parse("2026-11-03T01:02:03.123456Z");
        when(repository.observeAnomalies(any())).thenReturn(new PaymentAnomalyObservation(oldest.plusSeconds(60),
            new PaymentAnomalyObservation.Bucket(7, oldest),
            new PaymentAnomalyObservation.Bucket(2, oldest.plusSeconds(1)),
            new PaymentAnomalyObservation.Bucket(3, oldest.plusSeconds(2)),
            new PaymentAnomalyObservation.Bucket(4, oldest.plusSeconds(3)),
            new PaymentAnomalyObservation.Bucket(5, oldest.plusSeconds(4)),
            new PaymentAnomalyObservation.Bucket(6, oldest.plusSeconds(5))));
        var response = mvc.perform(get(PATH).header("Authorization", bearer("operations-service", "payment-service")))
            .andExpect(status().isOk()).andReturn().getResponse();
        var body = JsonMapper.builder().build().readTree(response.getContentAsString()).get("dataBody");
        List<String> lanes = List.of("unknown", "manualReviewUnknown", "inProgress", "returnedReady", "lateApproved", "doneOrderUnsynced");
        for (int index = 0; index < lanes.size(); index++) {
            var bucket = body.get(lanes.get(index));
            assertThat(bucket.get("count").asLong()).isEqualTo(index == 0 ? 7 : index + 1);
            assertThat(Instant.parse(bucket.get("oldestAt").asText())).isEqualTo(oldest.plusSeconds(index));
        }
        assertThat(Instant.parse(body.get("observedAt").asText())).isEqualTo(oldest.plusSeconds(60));
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
        when(repository.observeAnomalies(any())).thenThrow(new DataAccessResourceFailureException(sentinel));
        var response = mvc.perform(get(PATH).header("Authorization", bearer("operations-service", "payment-service")))
            .andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("PAYMENT_OBSERVATION_001").doesNotContain(sentinel);
        assertThat(output.getAll()).contains("PAYMENT_OBSERVATION_001", "DataAccessResourceFailureException").doesNotContain(sentinel);
    }

    private static PaymentAnomalyObservation facts(long count, Instant oldest) {
        var bucket = new PaymentAnomalyObservation.Bucket(count, oldest);
        return new PaymentAnomalyObservation(Instant.EPOCH, bucket, bucket, bucket, bucket, bucket, bucket);
    }

    private static String bearer(String caller, String audience) {
        return TestServiceTokens.bearer(caller, audience);
    }
}
