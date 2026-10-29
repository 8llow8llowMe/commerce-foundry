package com.commercefoundry.domainlayer.onboarding.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTaskItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.item.OnboardingTourItem;
import com.commercefoundry.domainlayer.onboarding.adapter.in.admin.dto.response.OnboardingAdminResponse;
import com.commercefoundry.domainlayer.onboarding.application.command.TourProgressUpdateCommand;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.in.OnboardingAdminUseCase;
import com.commercefoundry.domainlayer.onboarding.domain.model.MemberTaskStatus;
import com.commercefoundry.domainlayer.onboarding.domain.model.StoreTaskStatus;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourStatus;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 운영자 온보딩 API 의 HTTP 계약 — 권한 · 운영자(토큰의 memberId) 전달 · 검증 코드 · 도메인 오류 봉투 · 응답 모양.
 * 슬라이스라 보안 구성을 직접 import 하고, 테스트 키로 서명한 실제 access 토큰을 보낸다 ({@code security-guide.md} §5-3).
 */
@WebMvcTest(OnboardingAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class OnboardingAdminControllerTest {

    private static final String PATH = "/api/v1/admin/onboarding/me";
    private static final String TOUR_PATH = PATH + "/tours/console";
    private static final String BODY = """
        {"guideVersion": 1, "status": "IN_PROGRESS", "currentStep": 2}
        """;

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OnboardingAdminUseCase onboardingAdminUseCase;

    private static OnboardingTourItem item(TourStatus status, Integer step, Instant finishedAt) {
        return OnboardingTourItem.builder()
            .guideKey("console")
            .guideVersion(1)
            .status(status.toMetadata())
            .currentStep(step)
            .finishedAt(finishedAt)
            .build();
    }

    private static OnboardingTaskItem task(StoreTaskStatus storeStatus, MemberTaskStatus myStatus, Instant myCompletedAt) {
        return OnboardingTaskItem.builder()
            .taskKey("SHIPPING_POLICY_SAVED")
            .storeStatus(storeStatus.toMetadata())
            .myStatus(myStatus.toMetadata())
            .myCompletedAt(myCompletedAt)
            .build();
    }

    private MvcTestResult get(MemberRole role) {
        return mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange();
    }

    private MvcTestResult put(String uri, String body, MemberRole role) {
        return mvc.put().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(role)).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
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
        @DisplayName("토큰이 없으면 조회 · 기록 모두 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
        void anonymousIsUnauthorized() {
            SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            SecurityEnvelopeAssertions.assertEnvelope(
                mvc.put().uri(TOUR_PATH).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
            verifyNoInteractions(onboardingAdminUseCase);
        }

        @Test
        @DisplayName("CUSTOMER 면 조회 · 기록 모두 403 · SECURITY_005 이고 유스케이스를 부르지 않는다")
        void customerIsForbidden() {
            SecurityEnvelopeAssertions.assertEnvelope(get(MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            SecurityEnvelopeAssertions.assertEnvelope(put(TOUR_PATH, BODY, MemberRole.CUSTOMER).getResponse(), SecurityErrorCode.FORBIDDEN);
            verifyNoInteractions(onboardingAdminUseCase);
        }
    }

    @Nested
    @DisplayName("조회")
    class GetMine {

        @Test
        @DisplayName("ADMIN 은 200 — 운영자는 토큰의 memberId, tours 는 {guideKey, guideVersion, status{code,name,description}, currentStep, finishedAt}, "
            + "tasks 는 {taskKey, storeStatus{...}, myStatus{...}, myCompletedAt}")
        void returnsMine() {
            given(onboardingAdminUseCase.getMine(TestTokens.MEMBER_ID)).willReturn(new OnboardingAdminResponse(
                List.of(item(TourStatus.COMPLETED, 5, Instant.parse("2026-10-28T01:00:00Z")), item(TourStatus.NOT_STARTED, null, null)),
                List.of(task(StoreTaskStatus.READY, MemberTaskStatus.COMPLETED, Instant.parse("2026-10-27T02:00:00Z")))));

            MvcTestResult result = get(MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[0].guideKey").isEqualTo("console");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[0].guideVersion").asNumber().isEqualTo(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[0].status.code").isEqualTo("COMPLETED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[0].status.name").isEqualTo("완료");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[0].status.description").isNotNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[0].currentStep").asNumber().isEqualTo(5);
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[0].finishedAt").isEqualTo("2026-10-28T01:00:00Z");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[1].status.code").isEqualTo("NOT_STARTED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks").asArray().hasSize(1);
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].taskKey").isEqualTo("SHIPPING_POLICY_SAVED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].storeStatus.code").isEqualTo("READY");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].storeStatus.name").isEqualTo("준비됨");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].myStatus.code").isEqualTo("COMPLETED");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].myStatus.description").isNotNull();
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].myCompletedAt").isEqualTo("2026-10-27T02:00:00Z");
            assertThat(result).bodyJson().doesNotHavePath("$.dataBody.tours[0].memberId");
            assertThat(result).bodyJson().doesNotHavePath("$.dataBody.tasks[0].memberId");
            verify(onboardingAdminUseCase).getMine(TestTokens.MEMBER_ID);
        }

        @Test
        @DisplayName("담당 서비스 장애여도 200 — 그 업무는 UNAVAILABLE · UNAVAILABLE 이고 myCompletedAt 은 null(키는 남는다), 투어는 그대로다")
        void sourceOutageIsStillOk() {
            given(onboardingAdminUseCase.getMine(TestTokens.MEMBER_ID)).willReturn(new OnboardingAdminResponse(
                List.of(item(TourStatus.IN_PROGRESS, 2, null)), List.of(task(StoreTaskStatus.UNAVAILABLE, MemberTaskStatus.UNAVAILABLE, null))));

            MvcTestResult result = get(MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
            assertThat(result).bodyJson().extractingPath("$.dataBody.tours[0].status.code").isEqualTo("IN_PROGRESS");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].storeStatus.code").isEqualTo("UNAVAILABLE");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].myStatus.code").isEqualTo("UNAVAILABLE");
            assertThat(result).bodyJson().hasPath("$.dataBody.tasks[0].myCompletedAt");
            assertThat(result).bodyJson().extractingPath("$.dataBody.tasks[0].myCompletedAt").isNull();
        }
    }

    @Nested
    @DisplayName("기록")
    class Update {

        @Test
        @DisplayName("ADMIN 은 200 — 운영자는 토큰의 memberId 이고 경로 키 · 본문을 command 로 넘긴다")
        void records() {
            given(onboardingAdminUseCase.updateTour(anyLong(), any())).willReturn(item(TourStatus.IN_PROGRESS, 2, null));

            MvcTestResult result = put(TOUR_PATH, BODY, MemberRole.ADMIN);

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("IN_PROGRESS");
            assertThat(result).bodyJson().extractingPath("$.dataBody.currentStep").asNumber().isEqualTo(2);
            assertThat(result).bodyJson().extractingPath("$.dataBody.finishedAt").isNull();
            verify(onboardingAdminUseCase).updateTour(TestTokens.MEMBER_ID, new TourProgressUpdateCommand("console", 1, TourStatus.IN_PROGRESS, 2));
        }

        @Test
        @DisplayName("본문의 memberId 는 무시한다 — 남의 기록을 쓸 수 없다")
        void ignoresMemberIdInBody() {
            given(onboardingAdminUseCase.updateTour(anyLong(), any())).willReturn(item(TourStatus.SKIPPED, null, Instant.parse("2026-10-28T01:00:00Z")));

            assertThat(put(TOUR_PATH, """
                {"memberId": 999, "guideVersion": 1, "status": "SKIPPED"}
                """, MemberRole.ADMIN)).hasStatusOk();

            verify(onboardingAdminUseCase).updateTour(TestTokens.MEMBER_ID, new TourProgressUpdateCommand("console", 1, TourStatus.SKIPPED, null));
        }

        @ParameterizedTest(name = "{0} {1} → {2} ({3})")
        @CsvSource(delimiter = '|', textBlock = """
            /tours/Console   | {"guideVersion": 1, "status": "IN_PROGRESS"}                    | ONBOARDING_101 | guideKey
            /tours/1console  | {"guideVersion": 1, "status": "IN_PROGRESS"}                    | ONBOARDING_101 | guideKey
            /tours/console   | {"status": "IN_PROGRESS"}                                       | ONBOARDING_102 | guideVersion
            /tours/console   | {"guideVersion": 0, "status": "IN_PROGRESS"}                    | ONBOARDING_102 | guideVersion
            /tours/console   | {"guideVersion": 1}                                             | ONBOARDING_104 | status
            /tours/console   | {"guideVersion": 1, "status": "IN_PROGRESS", "currentStep": -1}  | ONBOARDING_105 | currentStep
            /tours/console   | {"guideVersion": 1, "status": "IN_PROGRESS", "currentStep": 201} | ONBOARDING_105 | currentStep
            /tours/console   | {"guideVersion": 1, "status": "DONE"}                           | ONBOARDING_100 |
            /tours/console   | {"guideVersion": "abc", "status": "IN_PROGRESS"}                | ONBOARDING_100 |
            /tours/console   | {broken                                                         | ONBOARDING_100 |
            """)
        @DisplayName("검증 코드 — 400 이고 유스케이스를 부르지 않는다. 본문 오류의 필드명은 파라미터 이름(request)이 아니라 본문 필드명이다")
        void invalid(String suffix, String body, String code, String field) {
            MvcTestResult result = put(PATH + suffix, body, MemberRole.ADMIN);

            assertFail(result, HttpStatus.BAD_REQUEST, code);
            if (field != null) {
                assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo(field);
            }
            verifyNoInteractions(onboardingAdminUseCase);
        }

        @Test
        @DisplayName("본문 오류가 둘이면 record 선언 순서다 — guideVersion 누락 + currentStep 201 은 대표 코드 ONBOARDING_102")
        void multipleBodyErrorsFollowDeclarationOrder() {
            MvcTestResult result = put(TOUR_PATH, """
                {"status": "IN_PROGRESS", "currentStep": 201}
                """, MemberRole.ADMIN);

            assertFail(result, HttpStatus.BAD_REQUEST, "ONBOARDING_102");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[0].field").isEqualTo("guideVersion");
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[1].field").isEqualTo("currentStep");
            verifyNoInteractions(onboardingAdminUseCase);
        }

        @Test
        @DisplayName("경로 오류와 본문 오류가 함께면 둘 다 싣는다 — 필드명은 guideKey 와 본문 필드명")
        void pathAndBodyErrorsTogether() {
            MvcTestResult result = put(PATH + "/tours/Console", """
                {"guideVersion": 1, "status": "IN_PROGRESS", "currentStep": 201}
                """, MemberRole.ADMIN);

            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].field").asArray().containsExactlyInAnyOrder("guideKey", "currentStep");
            verifyNoInteractions(onboardingAdminUseCase);
        }

        @Test
        @DisplayName("허용 목록에 없는 안내는 404 · ONBOARDING_001 봉투다")
        void notFound() {
            willThrow(new OnboardingException(OnboardingErrorCode.GUIDE_NOT_FOUND)).given(onboardingAdminUseCase).updateTour(anyLong(), any());

            assertFail(put(PATH + "/tours/unknown", BODY, MemberRole.ADMIN), HttpStatus.NOT_FOUND, "ONBOARDING_001");
        }

        @Test
        @DisplayName("오래된 버전은 409 · ONBOARDING_020, 계속 겹치면 409 · ONBOARDING_021, 배포 전 버전은 400 · ONBOARDING_103 봉투다")
        void conflicts() {
            willThrow(new OnboardingException(OnboardingErrorCode.STALE_GUIDE_VERSION)).given(onboardingAdminUseCase).updateTour(anyLong(), any());
            assertFail(put(TOUR_PATH, BODY, MemberRole.ADMIN), HttpStatus.CONFLICT, "ONBOARDING_020");

            willThrow(new OnboardingException(OnboardingErrorCode.CONCURRENT_UPDATE)).given(onboardingAdminUseCase).updateTour(anyLong(), any());
            assertFail(put(TOUR_PATH, BODY, MemberRole.ADMIN), HttpStatus.CONFLICT, "ONBOARDING_021");

            willThrow(new OnboardingException(OnboardingErrorCode.UNRELEASED_GUIDE_VERSION)).given(onboardingAdminUseCase).updateTour(anyLong(), any());
            assertFail(put(TOUR_PATH, BODY, MemberRole.ADMIN), HttpStatus.BAD_REQUEST, "ONBOARDING_103");
        }
    }
}
