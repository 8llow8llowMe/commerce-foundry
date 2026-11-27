package com.commercefoundry.domainlayer.visitanalytics.adapter.in.web.controller;

import com.commercefoundry.domainlayer.visitanalytics.application.exception.VisitAnalyticsErrorCode;
import com.commercefoundry.domainlayer.visitanalytics.application.exception.VisitAnalyticsException;
import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Tag(name = "방문 분석", description = "동의한 상품 상세 조회 수집")
@RestController
public class VisitEventController {

    private static final int BODY_LIMIT = 1024;
    private static final String CLIENT_SUBJECT_HEADER = "X-Client-Subject";
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern EVENT_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern PRODUCT_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern BOT = Pattern.compile("bot|crawl|spider|slurp|headless|preview", Pattern.CASE_INSENSITIVE);
    private final VisitAnalyticsUseCase useCase;
    private final ObjectReader reader;
    private final boolean enabled;

    public VisitEventController(VisitAnalyticsUseCase useCase, ObjectMapper mapper,
        @Value("${visit-analytics.enabled:${VISIT_ANALYTICS_ENABLED:false}}") boolean enabled) {
        this.useCase = useCase;
        this.reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.enabled = enabled;
    }

    @Operation(summary = "동의한 상품 상세 조회 수집",
        description = "익명·회원 공통. UUID v4 eventId, 문자열 productId, PRODUCT_VIEW type, boolean consent를 받습니다. 본문은 최대 1KiB입니다.")
    @PreAuthorize("permitAll()")
    @PostMapping("/api/v1/analytics/events")
    public ResponseEntity<Void> collect(HttpServletRequest request, @AuthenticationPrincipal MemberPrincipal member) throws IOException {
        if (!enabled) {
            return noContent();
        }

        var subjects = Collections.list(request.getHeaders(CLIENT_SUBJECT_HEADER));
        if (subjects.size() != 1 || !SUBJECT.matcher(subjects.getFirst()).matches()) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.CLIENT_SUBJECT_REQUIRED);
        }

        JsonNode event = readEvent(request);
        JsonNode consent = event.path("consent");
        if (!event.isObject() || event.size() != 4 || !consent.isBoolean() || !"PRODUCT_VIEW".equals(text(event, "type"))) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.INVALID_EVENT);
        }
        String eventId = text(event, "eventId");
        String productId = text(event, "productId");
        if (!EVENT_ID.matcher(eventId).matches() || !PRODUCT_ID.matcher(productId).matches()) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.INVALID_EVENT);
        }
        long id;
        try {
            id = Long.parseLong(productId);
        } catch (NumberFormatException invalidId) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.INVALID_EVENT);
        }
        if (consent.booleanValue() && !isExcluded(request, member)) {
            useCase.recordProductView(UUID.fromString(eventId), id);
        }
        return noContent();
    }

    private JsonNode readEvent(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        String encoding = request.getHeader(HttpHeaders.CONTENT_ENCODING);
        MediaType mediaType;
        try {
            mediaType = type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type);
            if ((!MediaType.APPLICATION_JSON.equalsTypeAndSubtype(mediaType) && !MediaType.TEXT_PLAIN.equalsTypeAndSubtype(mediaType))
                || (mediaType.getCharset() != null && !StandardCharsets.UTF_8.equals(mediaType.getCharset()))
                || (encoding != null && !"identity".equalsIgnoreCase(encoding))) {
                throw new VisitAnalyticsException(VisitAnalyticsErrorCode.UNSUPPORTED_BODY);
            }
        } catch (IllegalArgumentException invalidType) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.UNSUPPORTED_BODY);
        }

        if (request.getContentLengthLong() > BODY_LIMIT) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.BODY_TOO_LARGE);
        }
        byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
        if (bytes.length > BODY_LIMIT) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.BODY_TOO_LARGE);
        }
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return reader.readValue(json);
        } catch (JacksonException | CharacterCodingException invalidJson) {
            throw new VisitAnalyticsException(VisitAnalyticsErrorCode.INVALID_EVENT);
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() ? value.textValue() : "";
    }

    private static boolean isExcluded(HttpServletRequest request, MemberPrincipal member) {
        if (member != null && member.role().isOperator()) {
            return true;
        }
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        return agent == null || agent.isBlank() || BOT.matcher(agent).find();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
