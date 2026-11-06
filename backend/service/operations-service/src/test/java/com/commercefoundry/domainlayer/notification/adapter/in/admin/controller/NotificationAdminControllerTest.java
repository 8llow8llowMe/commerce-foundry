package com.commercefoundry.domainlayer.notification.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.notification.adapter.in.admin.dto.response.NotificationUnreadCountResponse;
import com.commercefoundry.domainlayer.notification.application.port.in.NotificationAdminUseCase;
import com.commercefoundry.global.properties.NotificationProperties;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * 어드민 알림 센터의 인가 계약 (#588) — 본인 수신함이라 {@code AdminAuthority.OPERATOR} 다. 운영자 역할 전체가 열리고, 수신자는 언제나 토큰의 memberId 다
 * (유스케이스 · 저장소가 수신자 아이디로 거른다). 시스템 경보는 발행 쪽이 대표 관리자(ADMIN · OWNER)에게만 만든다.
 */
@WebMvcTest(NotificationAdminController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class NotificationAdminControllerTest {

    private static final String PATH = "/api/v1/admin/notifications";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private NotificationAdminUseCase notificationAdminUseCase;

    @MockitoBean
    private NotificationProperties notificationProperties;

    @MockitoBean(name = "notificationStreamTaskScheduler")
    private TaskScheduler notificationStreamTaskScheduler;

    private static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("목록 · 읽지 않은 수 · 모두 읽음은 OPERATOR 라 운영자 역할 전체가 200 이고 수신자는 토큰의 memberId 다 (#588)")
    void everyOperatorMayUseOwnInbox(MemberRole role) {
        given(notificationAdminUseCase.list(TestTokens.MEMBER_ID, 0, 20)).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        given(notificationAdminUseCase.unreadCount(TestTokens.MEMBER_ID)).willReturn(new NotificationUnreadCountResponse(0));
        given(notificationAdminUseCase.markAllRead(eq(TestTokens.MEMBER_ID), any())).willReturn(new NotificationUnreadCountResponse(0));

        assertThat(mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange()).hasStatusOk();
        assertThat(mvc.get().uri(PATH + "/unread-count").header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange()).hasStatusOk();
        assertThat(mvc.post().uri(PATH + "/read-all").header(HttpHeaders.AUTHORIZATION, bearer(role)).exchange()).hasStatusOk();

        verify(notificationAdminUseCase).list(TestTokens.MEMBER_ID, 0, 20);
        verify(notificationAdminUseCase).unreadCount(TestTokens.MEMBER_ID);
        verify(notificationAdminUseCase).markAllRead(eq(TestTokens.MEMBER_ID), any());
    }

    @Test
    @DisplayName("고객 토큰은 403 · SECURITY_005, 토큰 없으면 401 · SECURITY_001 이고 유스케이스를 부르지 않는다")
    void customerAndAnonymousAreRejected() {
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(
            mvc.post().uri(PATH + "/1/read").header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange().getResponse(), SecurityErrorCode.FORBIDDEN);
        SecurityEnvelopeAssertions.assertEnvelope(mvc.get().uri(PATH).exchange().getResponse(), SecurityErrorCode.UNAUTHORIZED);
        verifyNoInteractions(notificationAdminUseCase);
    }
}
