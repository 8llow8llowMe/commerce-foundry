package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.presenter.LegalDocumentAdminPresenter;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRepository;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRevisionRepository;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.admin.presenter.StoreProfileAdminPresenter;
import com.commercefoundry.domainlayer.storeprofile.adapter.out.persistence.repository.StoreProfileRepository;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.ResourceServerSmokeTestSupport;
import com.commercefoundry.security.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:operations-audit-r1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(OperationsAdminAuditFlowTest.AuditConfiguration.class)
class OperationsAdminAuditFlowTest extends ResourceServerSmokeTestSupport {
    private static final String PROFILE = "/api/v1/admin/store/profile";
    private static final String LEGAL = "/api/v1/admin/store/legal-documents/TERMS/ko";
    private static final String BUSINESS = "BUSINESS_SENTINEL";
    private static final String DOCUMENT = "DOCUMENT_SENTINEL";
    private static final String EXCEPTION = "EXCEPTION_SENTINEL";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final org.slf4j.Logger DIAGNOSTIC = mock(org.slf4j.Logger.class);
    @MockitoSpyBean StoreProfileAdminPresenter profilePresenter;
    @MockitoSpyBean LegalDocumentAdminPresenter legalPresenter;
    @Autowired StoreProfileRepository profiles;
    @Autowired LegalDocumentRepository documents;
    @Autowired LegalDocumentRevisionRepository revisions;

    @TestConfiguration(proxyBeanMethods = false)
    static class AuditConfiguration {
        // Keep this audit fixture at database timestamp(6) precision; production clock policy is unchanged.
        @Bean
        @Primary
        Clock auditClock() {
            return Clock.fixed(Instant.parse("2026-11-04T00:00:00.123456Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        OperationsAdminAuditProcessor isolatedAuditProcessor() {
            return new OperationsAdminAuditProcessor(DIAGNOSTIC);
        }
    }

    @BeforeEach
    void clearData() {
        profiles.deleteAll();
        revisions.deleteAll();
        documents.deleteAll();
        reset(DIAGNOSTIC);
    }

    @Test
    void realJwtCommittedWritesHaveExactlyOneLineEachAndNoSensitiveFields() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        String token = bearer(MemberRole.ADMIN);
        String jwt = token.substring("Bearer ".length());
        String claims = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        String jti = JSON.readTree(claims).get("jti").stringValue();
        try {
            assertThat(request(put(PROFILE), profile(), token).getContentAsString()).contains(BUSINESS);
            assertThat(profiles.count()).isEqualTo(1);
            var saved = request(put(LEGAL + "/draft"), draft(), token);
            assertThat(saved.getStatus()).isEqualTo(200);
            assertThat(saved.getContentAsString()).contains(DOCUMENT);
            assertThat(documents.count()).isEqualTo(1);
            assertThat(revisions.count()).isEqualTo(1);
            var published = request(post(LEGAL + "/publish"), publish(), token);
            assertThat(published.getStatus()).isEqualTo(200);
            var repeated = request(post(LEGAL + "/publish"), publish(), token);
            assertThat(repeated.getStatus()).isEqualTo(200);
            assertThat(JSON.readTree(repeated.getContentAsString())).isEqualTo(JSON.readTree(published.getContentAsString()));
            assertThat(perform(get("/api/v1/store/legal-documents/TERMS")).getContentAsString()).contains(DOCUMENT);
            assertThat(request(put(PROFILE), profile(), token).getStatus()).isEqualTo(409);
            assertThat(request(put(LEGAL + "/draft"), draft(), token).getStatus()).isEqualTo(409);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                line("STORE_PROFILE_UPDATE", "store-profile:1", true),
                line("LEGAL_DOCUMENT_DRAFT_SAVE", "legal-document:terms-ko", true),
                line("LEGAL_DOCUMENT_PUBLISH", "legal-document:terms-ko", true),
                line("LEGAL_DOCUMENT_PUBLISH", "legal-document:terms-ko", true),
                line("STORE_PROFILE_UPDATE", "store-profile:1", false),
                line("LEGAL_DOCUMENT_DRAFT_SAVE", "legal-document:terms-ko", false));
            assertThat(jti).isNotBlank();
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(BUSINESS, DOCUMENT, jwt, jti, EXCEPTION);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writerAndDiagnosticFailurePreserveCommittedHttpResponsesAndOriginalConflicts(boolean diagnosticFails) throws Exception {
        var failure = new IllegalStateException(EXCEPTION, new RuntimeException("CAUSE_SENTINEL"));
        assertThat(failure.getMessage()).isEqualTo(EXCEPTION);
        if (diagnosticFails) {
            doThrow(new UnsupportedOperationException("DIAGNOSTIC_SENTINEL")).when(DIAGNOSTIC).warn(anyString(), any(), any(), any());
        }
        AtomicInteger attempts = new AtomicInteger();
        try (var writer = mockStatic(AdminAuditLog.class)) {
            writer.when(() -> AdminAuditLog.target(anyString(), anyString())).thenCallRealMethod();
            writer.when(() -> AdminAuditLog.write(anyString(), any(), anyString(), anyBoolean())).thenAnswer(invocation -> {
                attempts.incrementAndGet();
                throw failure;
            });
            String token = bearer(MemberRole.ADMIN);
            var profile = request(put(PROFILE), profile(), token);
            assertThat(profile.getStatus()).isEqualTo(200);
            assertThat(profile.getContentAsString()).contains(BUSINESS);
            assertThat(profiles.count()).isEqualTo(1);
            assertThat(perform(get("/api/v1/store/profile")).getContentAsString()).contains(BUSINESS);
            assertThat(request(put(LEGAL + "/draft"), draft(), token).getStatus()).isEqualTo(200);
            assertThat(documents.count()).isEqualTo(1);
            assertThat(revisions.count()).isEqualTo(1);
            var published = request(post(LEGAL + "/publish"), publish(), token);
            assertThat(published.getStatus()).isEqualTo(200);
            var retry = request(post(LEGAL + "/publish"), publish(), token);
            assertThat(retry.getStatus()).isEqualTo(200);
            assertThat(JSON.readTree(retry.getContentAsString())).isEqualTo(JSON.readTree(published.getContentAsString()));
            assertThat(perform(get("/api/v1/store/legal-documents/TERMS")).getContentAsString()).contains(DOCUMENT);
            var profileConflict = request(put(PROFILE), profile(), token);
            assertThat(profileConflict.getStatus()).isEqualTo(409);
            assertThat(profileConflict.getContentAsString()).contains("STORE_PROFILE_020");
            var legalConflict = request(put(LEGAL + "/draft"), draft(), token);
            assertThat(legalConflict.getStatus()).isEqualTo(409);
            assertThat(legalConflict.getContentAsString()).contains("LEGAL_DOCUMENT_020");
            var keyFailure = request(post(LEGAL.replace("TERMS", "INVALID") + "/publish"), publish(), token);
            assertThat(keyFailure.getStatus()).isEqualTo(400);
            assertThat(keyFailure.getContentAsString()).contains("LEGAL_DOCUMENT_100");
            assertThat(attempts.get()).isEqualTo(7);
            verify(DIAGNOSTIC, times(7)).warn(eq("Admin audit unavailable action={} actorMemberId={} exceptionType={}"),
                anyString(), eq(TestTokens.MEMBER_ID), eq(IllegalStateException.class.getName()));
            writer.verify(() -> AdminAuditLog.write("LEGAL_DOCUMENT_PUBLISH", TestTokens.MEMBER_ID, "-", false), times(1));
            verifyNoMoreInteractions(DIAGNOSTIC);
            assertThat(profiles.count()).isEqualTo(1);
            assertThat(revisions.count()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "draft", "publish"})
    void presenterFailureAfterCommitRemainsExactlyOneFailure(String operation) throws Exception {
        String token = bearer(MemberRole.ADMIN);
        if (operation.equals("publish")) {
            assertThat(request(put(LEGAL + "/draft"), draft(), token).getStatus()).isEqualTo(200);
        }
        var failure = new IllegalStateException(EXCEPTION, new RuntimeException("CAUSE_SENTINEL"));
        if (operation.equals("profile")) {
            doThrow(failure).when(profilePresenter).toResponse(any());
        } else {
            doThrow(failure).when(legalPresenter).detail(any());
        }
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var response = operation.equals("profile") ? request(put(PROFILE), profile(), token)
                : operation.equals("draft") ? request(put(LEGAL + "/draft"), draft(), token)
                : request(post(LEGAL + "/publish"), publish(), token);
            assertThat(response.getStatus()).isEqualTo(500);
            String action = operation.equals("profile") ? "STORE_PROFILE_UPDATE"
                : operation.equals("draft") ? "LEGAL_DOCUMENT_DRAFT_SAVE" : "LEGAL_DOCUMENT_PUBLISH";
            String target = operation.equals("profile") ? "store-profile:1" : "legal-document:terms-ko";
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(line(action, target, false));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(BUSINESS, DOCUMENT, EXCEPTION, "CAUSE_SENTINEL");
                assertThat(event.getThrowableProxy()).isNull();
            });
            if (operation.equals("profile")) {
                assertThat(profiles.count()).isEqualTo(1);
                assertThat(perform(get("/api/v1/store/profile")).getContentAsString()).contains(BUSINESS);
            } else {
                assertThat(documents.count()).isEqualTo(1);
                assertThat(revisions.count()).isEqualTo(1);
                if (operation.equals("publish")) {
                    assertThat(perform(get("/api/v1/store/legal-documents/TERMS")).getContentAsString()).contains(DOCUMENT);
                }
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void validationAndAuthorizationAreUnauditedButLegalDomainKeyFailureIsAudited() throws Exception {
        try (var writer = mockStatic(AdminAuditLog.class)) {
            for (String path : new String[]{PROFILE, LEGAL + "/draft"}) {
                assertThat(request(put(path), "{}", null).getStatus()).isEqualTo(401);
                assertThat(request(put(path), "{}", bearer(MemberRole.CUSTOMER)).getStatus()).isEqualTo(403);
                assertThat(request(put(path), "{}", bearer(MemberRole.ADMIN)).getStatus()).isEqualTo(400);
            }
            assertThat(request(put(PROFILE), profile().replace("US", "ZZ"), bearer(MemberRole.ADMIN)).getStatus()).isEqualTo(400);
            writer.verifyNoInteractions();
            var invalidKey = request(put(LEGAL.replace("TERMS", "INVALID") + "/draft"), draft(), bearer(MemberRole.ADMIN));
            assertThat(invalidKey.getStatus()).isEqualTo(400);
            assertThat(invalidKey.getContentAsString()).contains("LEGAL_DOCUMENT_100");
            writer.verify(() -> AdminAuditLog.write("LEGAL_DOCUMENT_DRAFT_SAVE", TestTokens.MEMBER_ID, "-", false), times(1));
            assertThat(profiles.count()).isZero();
            assertThat(documents.count()).isZero();
        }
    }

    private MockHttpServletResponse request(MockHttpServletRequestBuilder request, String body, String token) throws Exception {
        if (token != null) {
            request.header("Authorization", token);
        }
        return perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String line(String action, String target, boolean success) {
        return "action=" + action + " actorMemberId=" + TestTokens.MEMBER_ID + " target=" + target + " result=" + (success ? "SUCCESS" : "FAILURE");
    }

    private String profile() {
        return """
            {"expectedVersion":null,"storeName":"Example Store","companyName":"BUSINESS_SENTINEL",
            "representativeName":"Example Representative","countryCode":"US","businessRegistrationNumber":"EXAMPLE-REG-A1",
            "mailOrderRegistrationNumber":null,"address":"Example public address","customerServiceEmail":"help@example.invalid",
            "customerServicePhone":null,"customerServiceHours":null}
            """;
    }

    private String draft() {
        return "{\"expectedVersion\":null,\"title\":\"Title\",\"body\":\"DOCUMENT_SENTINEL\"}";
    }

    private String publish() {
        return "{\"expectedVersion\":0,\"revisionNumber\":1,\"reviewConfirmed\":true}";
    }
}
