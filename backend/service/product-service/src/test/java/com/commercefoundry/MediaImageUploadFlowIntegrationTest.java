package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.PresignedUpload;
import com.commercefoundry.storage.client.StoredObject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "store.media.public-base-url=https://cdn.example.com/media/")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class MediaImageUploadFlowIntegrationTest {
    private static final String PATH = "/api/v1/admin/displays/image-uploads";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private MockMvcTester mvc;
    @Autowired private ProductRepository products;
    @MockitoBean private ObjectStorageClient storage;

    @BeforeEach
    void storage() {
        when(storage.presignPut(anyString(), anyString(), anyLong())).thenReturn(
            new PresignedUpload("https://storage.example.com/private?signature=secret", "PUT", Map.of("Content-Type", "image/png"), Instant.now().plusSeconds(300)));
        when(storage.head(anyString())).thenReturn(Optional.of(new StoredObject(8, "image/png", "\"opaque-version\"")));
        when(storage.readRange(anyString(), eq(12), eq("\"opaque-version\"")))
            .thenReturn(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        when(storage.publicUrlOf(anyString())).thenAnswer(call -> "https://cdn.example.com/media/" + call.getArgument(0));
    }

    @Test
    void signedJwtAuthorizationValidationAndOwner() {
        assertThat(mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(post(PATH, "{}", MemberRole.CUSTOMER, 7)).hasStatus(HttpStatus.FORBIDDEN);
        String confirmPath = PATH + "/11111111-1111-1111-1111-111111111111.png/confirm";
        assertThat(mvc.post().uri(confirmPath).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(post(confirmPath, null, MemberRole.CUSTOMER, 7)).hasStatus(HttpStatus.FORBIDDEN);
        verifyNoInteractions(storage);
        var malformed = post(PATH, "{", MemberRole.ADMIN, 7);
        assertThat(malformed).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(malformed).at("/dataHeader/resultCode").asString()).isEqualTo("PRODUCT_100");
        var invalid = post(PATH, "{\"contentType\":\"image/png\",\"contentLength\":0}", MemberRole.ADMIN, 7);
        assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(invalid).at("/dataHeader/resultCode").asString()).isEqualTo("MEDIA_UPLOAD_104");
        verifyNoInteractions(storage);
        String key = issue();
        verify(storage).presignPut("uploads/display-media/7/" + key, "image/png", 8);
        when(storage.head("uploads/display-media/8/" + key)).thenReturn(Optional.empty());
        var other = post(PATH + "/" + key + "/confirm", null, MemberRole.ADMIN, 8);
        assertThat(other).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(body(other).at("/dataHeader/resultCode").asString()).isEqualTo("MEDIA_UPLOAD_001");
        Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
        try {
            var invalidKey = post(PATH + "/not-a-key/confirm", null, MemberRole.ADMIN, 7);
            assertThat(invalidKey).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(body(invalidKey).at("/dataHeader/resultCode").asString()).isEqualTo("MEDIA_UPLOAD_106");
            assertThat(audit.list).as("경로 형식 검증 실패는 업무 감사 전에 거절한다").isEmpty();
        } finally {
            auditLogger.detachAppender(audit);
            audit.stop();
        }
    }

    @Test
    void confirmedUrlPersistsThroughRealDisplayAndLookWithoutDimensions() {
        String key = issue();
        var confirmed = post(PATH + "/" + key + "/confirm", null, MemberRole.ADMIN, 7);
        assertThat(confirmed).hasStatusOk();
        assertThat(confirmed.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        String url = body(confirmed).at("/dataBody/publicUrl").asString();
        assertThat(body(confirmed).at("/dataBody/contentLength").asLong()).isEqualTo(8);
        var section = post("/api/v1/admin/displays/sections", """
            {"sectionType":"HERO","visible":true,"items":[{"itemType":"MEDIA","media":{"mediaType":"IMAGE","pcUrl":"%s"}}]}
            """.formatted(url), MemberRole.ADMIN, 7);
        assertThat(section).hasStatus(HttpStatus.CREATED);
        assertThat(body(mvc.get().uri("/api/v1/displays/main").exchange()).at("/dataBody/sections/0/items/0/media/pcUrl").asString()).isEqualTo(url);
        products.saveAndFlush(ProductEntity.builder().id(508_001L).categoryId(10L).name("media upload fixture")
            .saleStatus(SaleStatus.ON_SALE).visible(true).salePriceAmount(1000L).salePriceCurrency(CurrencyCode.KRW).customOrder(false).build());
        var look = post("/api/v1/admin/looks", """
            {"title":"uploaded image","image":{"url":"%s"},"visible":true,"productIds":[508001]}
            """.formatted(url), MemberRole.ADMIN, 7);
        assertThat(look).hasStatus(HttpStatus.CREATED);
        String lookId = body(look).at("/dataBody/lookId").asString();
        assertThat(body(mvc.get().uri("/api/v1/looks/" + lookId).exchange()).at("/dataBody/image/url").asString()).isEqualTo(url);
        verify(storage).readRange("uploads/display-media/7/" + key, 12, "\"opaque-version\"");
        verify(storage).copy(eq("uploads/display-media/7/" + key), startsWith("products/media/7/"),
            eq("image/png"), eq("public, max-age=31536000, immutable"), eq("\"opaque-version\""));
        verify(storage, never()).delete(anyString());
    }

    private String issue() {
        var issued = post(PATH, "{\"contentType\":\" IMAGE/PNG \",\"contentLength\":8}", MemberRole.ADMIN, 7);
        assertThat(issued).hasStatus(HttpStatus.CREATED);
        assertThat(issued.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        return body(issued).at("/dataBody/uploadKey").asString();
    }

    private MvcTestResult post(String path, String content, MemberRole role, long actor) {
        var request = mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION,
            "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(actor, role).tokenValue());
        if (content != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(content);
        }
        return request.exchange();
    }

    private static JsonNode body(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
