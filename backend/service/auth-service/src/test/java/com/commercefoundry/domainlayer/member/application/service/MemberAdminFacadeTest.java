package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminMemberItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.MemberAdminPresenter;
import com.commercefoundry.domainlayer.member.application.info.AdminMemberInfo;
import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.query.MemberListQueryResult;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberAdminProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Processor 는 실제 것(저장소 포트만 mock)으로 Info 변환까지 본다. 감사 포트는 mock 이다.
 */
class MemberAdminFacadeTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-18T03:00:00Z");

    private final MemberRepositoryPort memberRepositoryPort = mock(MemberRepositoryPort.class);
    private final AdminAuditPort adminAuditPort = mock(AdminAuditPort.class);
    private final MemberAdminFacade facade = new MemberAdminFacade(
        new MemberAdminProcessor(memberRepositoryPort), new AdminAuditProcessor(adminAuditPort), new MemberAdminPresenter()
    );

    @Test
    @DisplayName("저장소 페이지를 마스킹한 응답으로 바꾸고, 누가 어느 조건 · 페이지를 봤는지 감사한다")
    void searchMembers() {
        MemberListQueryResult row = new MemberListQueryResult(5L, "customer@example.com", "홍길동", "01012345678", MemberRole.CUSTOMER, CREATED_AT);
        given(memberRepositoryPort.findPage(MemberRole.CUSTOMER, 2, 10)).willReturn(new PageImpl<>(List.of(row), PageRequest.of(2, 10), 21));

        PageResponse<AdminMemberItem> response = facade.searchMembers(9L, MemberRole.CUSTOMER, 2, 10);

        assertThat(response.totalElements()).isEqualTo(21);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.contents()).singleElement().satisfies(item -> {
            assertThat(item.memberId()).isEqualTo("5");
            assertThat(item.email()).isEqualTo("cu***@e***.com");
            assertThat(item.name()).isEqualTo("홍*동");
        });
        verify(adminAuditPort).membersSearched(9L, MemberRole.CUSTOMER, 2, 10);
    }

    @Test
    @DisplayName("역할 조건이 없으면(null) 전체로 조회하고 그대로 감사한다")
    void searchAll() {
        given(memberRepositoryPort.findPage(null, 0, 20)).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(facade.searchMembers(9L, null, 0, 20).contents()).isEmpty();
        verify(adminAuditPort).membersSearched(9L, null, 0, 20);
    }

    @Test
    @DisplayName("Info 는 개인정보를 toString 에 싣지 않는다")
    void infoToStringHidesPersonalData() {
        AdminMemberInfo info = new AdminMemberInfo(5L, "customer@example.com", "홍길동", "01012345678", MemberRole.CUSTOMER, CREATED_AT);

        assertThat(info.toString()).doesNotContain("customer@example.com", "홍길동", "01012345678");
    }
}
