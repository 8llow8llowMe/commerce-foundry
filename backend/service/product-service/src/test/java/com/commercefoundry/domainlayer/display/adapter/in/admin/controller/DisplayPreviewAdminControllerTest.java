package com.commercefoundry.domainlayer.display.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplayPreviewAdminResponse;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayPreviewAdminUseCase;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(DisplayPreviewAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class DisplayPreviewAdminControllerTest {

    private static final String PATH = "/api/v1/admin/displays/preview";
    private static final String STORY = """
        {"sectionType":"STORY","title":"  초안  ","visible":true,"items":[]}
        """;
    @Autowired
    private MockMvcTester mvc;
    @MockitoBean
    private DisplayPreviewAdminUseCase useCase;

    @Test
    void adminReceivesNoStoreAndEmptyDraftIsAllowed() {
        given(useCase.preview(anyList())).willReturn(new DisplayPreviewAdminResponse(List.of()));
        var result = exchange("{\"sections\":[]}", MemberRole.ADMIN);
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        verify(useCase).preview(List.of());
    }

    @Test
    void acceptsFiftyAndNormalizesNestedCommands() {
        given(useCase.preview(anyList())).willReturn(new DisplayPreviewAdminResponse(List.of()));
        String body = "{\"sections\":[" + IntStream.range(0, 50).mapToObj(i -> STORY).collect(Collectors.joining(",")) + "]}";
        assertThat(exchange(body, MemberRole.ADMIN)).hasStatusOk();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DisplaySectionUpsertCommand>> commands = ArgumentCaptor.forClass(List.class);
        verify(useCase).preview(commands.capture());
        assertThat(commands.getValue()).hasSize(50);
        assertThat(commands.getValue().getFirst().title()).isEqualTo("초안");
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
        {}, PRODUCT_166
        '{"sections":null}', PRODUCT_166
        '{"sections":[null]}', PRODUCT_168
        '{"sections":[{"visible":true,"items":[]}]}', PRODUCT_140
        '{"sections":[{"sectionType":"STORY","visible":true,"items":[null]}]}', PRODUCT_149
        """)
    void rejectsInvalidNestedBody(String body, String code) {
        var result = exchange(body, MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(JsonMapper.builder().build().readTree(result.getResponse().getContentAsByteArray()).at("/dataHeader/resultCode").asString()).isEqualTo(code);
        verifyNoInteractions(useCase);
    }

    @Test
    void rejectsFiftyOneSections() {
        var result = exchange("{\"sections\":[" + IntStream.range(0, 51).mapToObj(i -> STORY).collect(Collectors.joining(",")) + "]}", MemberRole.ADMIN);
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(JsonMapper.builder().build().readTree(result.getResponse().getContentAsByteArray()).at("/dataHeader/resultCode").asString()).isEqualTo("PRODUCT_167");
        verifyNoInteractions(useCase);
    }

    @Test
    void requiresAdmin() {
        SecurityEnvelopeAssertions.assertEnvelope(exchange("{\"sections\":[]}", null).getResponse(), SecurityErrorCode.UNAUTHORIZED);
        SecurityEnvelopeAssertions.assertEnvelope(exchange("{\"sections\":[]}", MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
        verifyNoInteractions(useCase);
    }

    private MvcTestResult exchange(String body, MemberRole role) {
        var request = mvc.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
        if (role != null) {
            request = request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue());
        }
        return request.exchange();
    }
}
