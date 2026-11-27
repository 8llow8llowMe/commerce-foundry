package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationInternalUseCase;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository.GuestSessionRepository;
import com.commercefoundry.domainlayer.guestsession.application.port.out.GuestSessionCryptoPort;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비회원 상담 자격 공개 발급 (#470 B2b-1). Swagger 고객 그룹 계약도 같이 보므로 문서를 연다 —
 * {@link SupportServiceApplicationTests.SwaggerOpened} 와 같은 설정이라 컨텍스트를 같이 쓴다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@ExtendWith(OutputCaptureExtension.class)
class GuestSessionWebTest {
    private static final String PATH = "/api/v1/support/guest-sessions";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired ConversationInternalUseCase conversations;
    @Autowired GuestSessionCryptoPort crypto;
    @Autowired GuestSessionRepository sessions;

    @Test
    void anonymousIssueReturns201NoStoreTokenAndSevenDayExpiryWithoutSessionId(CapturedOutput output) throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String body = mvc.perform(post(PATH))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.dataHeader.success").value(true))
            .andReturn().getResponse().getContentAsString();
        Instant after = Instant.now();

        JsonNode data = JSON.readTree(body).path("dataBody");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("token", "expiresAt");
        String token = data.path("token").asString();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        Instant expiresAt = Instant.parse(data.path("expiresAt").asString());
        assertThat(expiresAt).isBetween(before.plus(Duration.ofDays(7)), after.plus(Duration.ofDays(7)));

        assertThat(output.getAll()).doesNotContain(token).doesNotContain(crypto.fingerprint(token));
    }

    @Test
    void validMemberOrOperatorJwtIsForbiddenAndInvalidBearerIsUnauthorized() throws Exception {
        mvc.perform(post(PATH).header("Authorization", bearer(TestTokens.MEMBER_ID, MemberRole.CUSTOMER)))
            .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.dataBody").doesNotExist());
        for (MemberRole role : MemberRole.OPERATOR_ROLES) {
            mvc.perform(post(PATH).header("Authorization", bearer(TestTokens.MEMBER_ID, role)))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
        }
        String expired = "Bearer " + TestTokens.issuer(Instant.now().minusSeconds(7200)).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
        for (String authorization : new String[]{"Bearer broken", expired}) {
            mvc.perform(post(PATH).header("Authorization", authorization))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void issuedTokenAuthenticatesExistingGuestConversationEndpoints() throws Exception {
        String token = issue();
        mvc.perform(get("/api/v1/support/conversations/1").header("X-Support-Guest-Token", token))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.dataHeader.resultCode").value("SUPPORT_010"));

        long own = conversations.createForGuest(token, ConversationLocale.KO).id();
        mvc.perform(get("/api/v1/support/conversations/" + own).header("X-Support-Guest-Token", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.id").value(String.valueOf(own)));
        mvc.perform(get("/api/v1/support/conversations/" + own).header("X-Support-Guest-Token", issue()))
            .andExpect(status().isNotFound());
    }

    @Test
    void trailingSlashAndSubPathsAreNotFoundAndIssueNothing() throws Exception {
        long before = sessions.count();
        for (String path : new String[]{PATH + "/", PATH + "/x"}) {
            mvc.perform(post(path)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        }
        assertThat(sessions.count()).isEqualTo(before);
    }

    @Test
    void existingGuestHeaderIsIgnoredOnIssueAndPreviousTokenStaysValid() throws Exception {
        String previous = issue();
        long own = conversations.createForGuest(previous, ConversationLocale.EN).id();

        String body = mvc.perform(post(PATH).header("X-Support-Guest-Token", previous))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(body).path("dataBody").path("token").asString()).isNotEqualTo(previous);

        mvc.perform(get("/api/v1/support/conversations/" + own).header("X-Support-Guest-Token", previous))
            .andExpect(status().isOk()).andExpect(jsonPath("$.dataBody.id").value(String.valueOf(own)));
    }

    @Test
    void everySupportPathIsNoStoreIncludingCollectionPost() throws Exception {
        mvc.perform(post("/api/v1/support/conversations")).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/support/conversations/1")).andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void customerSwaggerGroupDocumentsAnonymousIssueAs201WithoutSessionId() throws Exception {
        String docs = mvc.perform(get("/v3/api-docs/customer")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode root = JSON.readTree(docs);
        JsonNode operation = root.path("paths").path(PATH).path("post");
        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("responses").has("201")).isTrue();
        assertThat(operation.path("responses").has("200")).isFalse();
        assertThat(operation.path("responses").path("201").path("content").toString()).contains("Response");
        assertThat(operation.has("requestBody")).isFalse();
        assertThat(operation.has("security")).as("비로그인 전용이라 bearer 요구를 선언하지 않는다").isFalse();
        JsonNode properties = root.path("components").path("schemas").path("Issued").path("properties");
        assertThat(properties.propertyNames()).containsExactlyInAnyOrder("token", "expiresAt");

        String admin = mvc.perform(get("/v3/api-docs/admin")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(admin).path("paths").has(PATH)).isFalse();
    }

    private String issue() throws Exception {
        String body = mvc.perform(post(PATH)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("dataBody").path("token").asString();
    }

    private static String bearer(long id, MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(id, role).tokenValue();
    }
}
