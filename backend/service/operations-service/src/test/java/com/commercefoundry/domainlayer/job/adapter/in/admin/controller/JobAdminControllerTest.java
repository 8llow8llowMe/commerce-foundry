package com.commercefoundry.domainlayer.job.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.item.JobAdminItem;
import com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response.JobAdminResponse;
import com.commercefoundry.domainlayer.job.application.command.JobAdminSearchCommand;
import com.commercefoundry.domainlayer.job.application.exception.JobErrorCode;
import com.commercefoundry.domainlayer.job.application.exception.JobException;
import com.commercefoundry.domainlayer.job.application.model.JobStatusFilter;
import com.commercefoundry.domainlayer.job.application.port.in.JobAdminUseCase;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.support.SecurityEnvelopeAssertions;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 어드민 작업 API 의 HTTP 계약 — 권한 · 취소한 어드민(토큰의 memberId) 전달 · 파라미터 검증 코드 · 도메인 오류 봉투 · 응답 모양.
 * 슬라이스라 보안 구성을 직접 import 하고, 테스트 키로 서명한 실제 access 토큰을 보낸다 ({@code security-guide.md} §5-3).
 * 어드민 URL 규칙 전체는 {@code OperationsServiceSecuritySmokeTest} 가 본다.
 */
@WebMvcTest(JobAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class JobAdminControllerTest {

    private static final String PATH = "/api/v1/admin/jobs";
    private static final String JOB_PATH = PATH + "/4611686018427387904";
    private static final String CANCEL_PATH = JOB_PATH + "/cancel";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private JobAdminUseCase jobAdminUseCase;

    private static JobAdminResponse response() {
        return JobAdminResponse.builder()
            .jobId("4611686018427387904")
            .type("SALES_REPORT_EXPORT")
            .status(CodeNameDescriptionMetadata.of("RUNNING", "실행 중", "워커가 가져가 실행하고 있습니다."))
            .progressPercent(40)
            .attempt(1)
            .maxAttempts(3)
            .cancelRequested(true)
            .cancelRequestedByMemberId(String.valueOf(TestTokens.MEMBER_ID))
            .requestedByMemberId("4611686018427387903")
            .payloadJson("{\"month\":\"2026-09\"}")
            .scheduledAt(Instant.parse("2026-10-24T03:00:00Z"))
            .startedAt(Instant.parse("2026-10-24T03:00:02Z"))
            .build();
    }

    private static JobAdminItem item() {
        return JobAdminItem.builder()
            .jobId("4611686018427387904")
            .type("SALES_REPORT_EXPORT")
            .status(CodeNameDescriptionMetadata.of("FAILED", "실패", "재시도 상한에 닿았거나 다시 시도할 수 없는 오류로 끝났습니다."))
            .attempt(3)
            .maxAttempts(3)
            .errorCode("UPSTREAM_TIMEOUT")
            .scheduledAt(Instant.parse("2026-10-24T03:00:00Z"))
            .finishedAt(Instant.parse("2026-10-24T03:10:00Z"))
            .build();
    }

    private MvcTestResult get(String uri, MemberRole role) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult post(String uri, MemberRole role) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    private static void assertFail(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
        assertThat(result).bodyJson().extractingPath("$.dataBody").isNull();
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("CUSTOMER 면 목록 · 상세 · 취소 모두 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(get(PATH, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(get(JOB_PATH, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(post(CANCEL_PATH, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(jobAdminUseCase);
        }

        @Test
        @DisplayName("토큰이 없으면 목록 · 상세 · 취소 모두 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(JOB_PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            SecurityEnvelopeAssertions.assertEnvelope(mvc.post().uri(CANCEL_PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(jobAdminUseCase);
        }
    }

    @Nested
    @DisplayName("목록")
    class Search {

        @Test
        @DisplayName("기본값은 전체 · page 0 · size 20 이고, 아이디는 문자열 · 상태는 {code, name, description} · 시각은 ISO-8601 이다")
        void defaults() {
            given(jobAdminUseCase.getJobs(any())).willReturn(new PageResponse<>(List.of(item()), 0, 20, 1, 1));

            MvcTestResult result = get(PATH, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].jobId").asString().isEqualTo("4611686018427387904");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].status.code").isEqualTo("FAILED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].errorCode").isEqualTo("UPSTREAM_TIMEOUT");
            assertThat(result).bodyJson().extractingPath("$.dataBody.contents[0].finishedAt").isEqualTo("2026-10-24T03:10:00Z");
            assertThat(result).bodyJson().extractingPath("$.dataBody.totalElements").asNumber().isEqualTo(1);
            verify(jobAdminUseCase).getJobs(new JobAdminSearchCommand(null, null, 0, 20));
        }

        @Test
        @DisplayName("상태 · 종류 · 페이지를 command 로 넘긴다")
        void passesFilters() {
            given(jobAdminUseCase.getJobs(any())).willReturn(new PageResponse<>(List.of(), 2, 50, 0, 0));

            assertThat(get(PATH + "?status=FAILED&type=SALES_REPORT_EXPORT&page=2&size=50", MemberRole.ADMIN)).hasStatusOk();

            verify(jobAdminUseCase).getJobs(new JobAdminSearchCommand(JobStatusFilter.FAILED, "SALES_REPORT_EXPORT", 2, 50));
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "?page=-1 | JOB_101",
            "?size=0 | JOB_102",
            "?size=101 | JOB_102",
            "?type=sales | JOB_103",
            "?type=SALES-REPORT | JOB_103",
            "?status=DONE | JOB_198",
            "?page=abc | JOB_198"
        })
        @DisplayName("파라미터 검증 코드 — 유스케이스를 부르지 않는다")
        void invalid(String query, String code) {
            assertFail(get(PATH + query, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, code);
            verifyNoInteractions(jobAdminUseCase);
        }
    }

    @Nested
    @DisplayName("상세")
    class Detail {

        @Test
        @DisplayName("상세를 200 으로 낸다 — 입력 JSON 은 문자열 원문이고 워커 아이디는 없다")
        void returnsDetail() {
            given(jobAdminUseCase.getJob(4611686018427387904L)).willReturn(response());

            MvcTestResult result = get(JOB_PATH, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.payloadJson").isEqualTo("{\"month\":\"2026-09\"}");
            assertThat(result).bodyJson().extractingPath("$.dataBody.progressPercent").asNumber().isEqualTo(40);
            assertThat(result).bodyJson().doesNotHavePath("$.dataBody.workerId");
        }

        @Test
        @DisplayName("없는 작업은 404 · JOB_001")
        void notFound() {
            given(jobAdminUseCase.getJob(anyLong())).willThrow(new JobException(JobErrorCode.NOT_FOUND_JOB));

            assertFail(get(JOB_PATH, MemberRole.ADMIN), HttpStatus.NOT_FOUND, "JOB_001");
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {
            "/0 | JOB_104",
            "/-1 | JOB_104",
            "/abc | JOB_198"
        })
        @DisplayName("아이디 검증 코드 — 유스케이스를 부르지 않는다")
        void invalidId(String suffix, String code) {
            assertFail(get(PATH + suffix, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, code);
            assertFail(post(PATH + suffix + "/cancel", MemberRole.ADMIN), HttpStatus.BAD_REQUEST, code);
            verifyNoInteractions(jobAdminUseCase);
        }
    }

    @Nested
    @DisplayName("취소")
    class Cancel {

        @Test
        @DisplayName("취소한 어드민은 토큰의 memberId 로 넘기고 200 으로 결과를 낸다")
        void cancels() {
            given(jobAdminUseCase.cancel(4611686018427387904L, TestTokens.MEMBER_ID)).willReturn(response());

            MvcTestResult result = post(CANCEL_PATH, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.cancelRequested").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("RUNNING");
            verify(jobAdminUseCase).cancel(4611686018427387904L, TestTokens.MEMBER_ID);
        }

        @Test
        @DisplayName("끝난 작업은 409 · JOB_022, 워커와 계속 겹치면 409 · JOB_021")
        void conflicts() {
            willThrow(new JobException(JobErrorCode.JOB_ALREADY_FINISHED)).given(jobAdminUseCase).cancel(anyLong(), anyLong());
            assertFail(post(CANCEL_PATH, MemberRole.ADMIN), HttpStatus.CONFLICT, "JOB_022");

            willThrow(new JobException(JobErrorCode.JOB_CONFLICT)).given(jobAdminUseCase).cancel(anyLong(), anyLong());
            assertFail(post(CANCEL_PATH, MemberRole.ADMIN), HttpStatus.CONFLICT, "JOB_021");
        }
    }
}
