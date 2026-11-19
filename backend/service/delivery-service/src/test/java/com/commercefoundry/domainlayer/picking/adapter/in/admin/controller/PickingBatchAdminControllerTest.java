package com.commercefoundry.domainlayer.picking.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.picking.adapter.in.admin.dto.response.PickingBatchAdminResponse;
import com.commercefoundry.domainlayer.picking.application.exception.PickingErrorCode;
import com.commercefoundry.domainlayer.picking.application.exception.PickingException;
import com.commercefoundry.domainlayer.picking.application.port.in.PickingBatchAdminUseCase;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(PickingBatchAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class PickingBatchAdminControllerTest {

    private static final String PATH = PickingBatchAdminController.PATH;
    private static final String DETAIL = PATH + "/1";
    private static final String CHECK = DETAIL + "/orders/2/checks";
    private static final String CREATE_BODY = """
        {"orderIds":["2"]}
        """;
    private static final String CHECK_BODY = """
        {"quantities":{"10":2}}
        """;
    private static final String BATCH_ID = "4611686018427387904";
    private static final long ACTOR = TestTokens.MEMBER_ID;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PickingBatchAdminUseCase useCase;

    @Test
    void managerCreatesAndChecksWhileStaffCanRead() {
        PickingBatchAdminResponse response = new PickingBatchAdminResponse(BATCH_ID, 0L, false, 2, List.of(), List.of(), List.of(), List.of());
        given(useCase.create(List.of(2L), ACTOR)).willReturn(response);
        given(useCase.detail(1L)).willReturn(response);
        given(useCase.check(1L, 2L, Map.of(10L, 2L), ACTOR)).willReturn(response);

        var created = post(PATH, CREATE_BODY, MemberRole.MANAGER);
        assertThat(created).hasStatus(201).hasHeader(HttpHeaders.LOCATION, PATH + "/" + BATCH_ID);
        assertThat(created).bodyJson().extractingPath("$.dataBody.batchId").isEqualTo(BATCH_ID);
        assertThat(get(DETAIL, MemberRole.STAFF)).hasStatusOk();
        assertThat(post(CHECK, CHECK_BODY, MemberRole.MANAGER)).hasStatusOk();
    }

    @Test
    void rejectsAnonymousCustomersAndStaffWrites() {
        assertCode(mvc.get().uri(DETAIL).exchange(), 401, "SECURITY_001");
        assertCode(get(DETAIL, MemberRole.CUSTOMER), 403, "SECURITY_005");
        for (MemberRole role : List.of(MemberRole.CUSTOMER, MemberRole.STAFF)) {
            assertCode(post(PATH, CREATE_BODY, role), 403, "SECURITY_005");
            assertCode(post(CHECK, CHECK_BODY, role), 403, "SECURITY_005");
        }
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        {"orderIds":[]} | DELIVERY_150
        {"orderIds":["2","2"]} | DELIVERY_155
        {"orderIds":[null]} | DELIVERY_151
        {"orderIds":["9223372036854775808"]} | DELIVERY_151
        """)
    void rejectsInvalidOrderIds(String body, String code) {
        assertCode(post(PATH, body, MemberRole.MANAGER), 400, code);
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        {} | DELIVERY_152
        {"quantities":{"9223372036854775808":1}} | DELIVERY_153
        {"quantities":{"10":0}} | DELIVERY_154
        {"quantities":{"10":null}} | DELIVERY_154
        {"quantities":{"10":1.5}} | DELIVERY_154
        {"quantities":{"10":9223372036854775808}} | DELIVERY_154
        """)
    void rejectsInvalidCheck(String body, String code) {
        assertCode(post(CHECK, body, MemberRole.MANAGER), 400, code);
        verifyNoInteractions(useCase);
    }

    @Test
    void rejectsNonPositiveCheckOrderIdAndNonNumericBatchId() {
        assertCode(post(DETAIL + "/orders/0/checks", CHECK_BODY, MemberRole.MANAGER), 400, "DELIVERY_151");
        assertCode(get(PATH + "/bad", MemberRole.STAFF), 400, "DELIVERY_198");
        verifyNoInteractions(useCase);
    }

    @Test
    void returnsPickingErrorEnvelopes() {
        given(useCase.detail(1L)).willThrow(new PickingException(PickingErrorCode.BATCH_NOT_FOUND));
        given(useCase.create(List.of(2L), ACTOR)).willThrow(new PickingException(PickingErrorCode.NO_ELIGIBLE_ORDER),
            new PickingException(PickingErrorCode.SNAPSHOT_UNAVAILABLE));
        given(useCase.check(1L, 2L, Map.of(10L, 2L), ACTOR)).willThrow(new PickingException(PickingErrorCode.CONCURRENTLY_CHANGED));

        assertCode(get(DETAIL, MemberRole.STAFF), 404, "DELIVERY_034");
        assertCode(post(PATH, CREATE_BODY, MemberRole.MANAGER), 409, "DELIVERY_033");
        assertCode(post(PATH, CREATE_BODY, MemberRole.MANAGER), 503, "DELIVERY_036");
        assertCode(post(CHECK, CHECK_BODY, MemberRole.MANAGER), 409, "DELIVERY_037");
    }

    private MvcTestResult get(String path, MemberRole role) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult post(String path, String body, MemberRole role) {
        return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer(role))
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(ACTOR, role).tokenValue();
    }

    private void assertCode(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
    }
}
