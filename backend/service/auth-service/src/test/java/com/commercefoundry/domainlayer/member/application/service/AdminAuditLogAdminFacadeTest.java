package com.commercefoundry.domainlayer.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminAuditLogAdminItem;
import com.commercefoundry.domainlayer.member.adapter.in.admin.presenter.AdminAuditLogAdminPresenter;
import com.commercefoundry.domainlayer.member.application.command.AdminAuditLogSearchCommand;
import com.commercefoundry.domainlayer.member.application.service.processor.AdminAuditLogQueryProcessor;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberNameQueryProcessor;
import com.commercefoundry.domainlayer.member.domain.model.AdminAuditLog;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogAdminFacadeTest {

    private static final Instant AT = Instant.parse("2026-10-24T03:00:00Z");
    private static final AdminAuditLogSearchCommand COMMAND = new AdminAuditLogSearchCommand(null, null, null, null, 0, 20);

    @Mock
    private AdminAuditLogQueryProcessor adminAuditLogQueryProcessor;

    @Mock
    private MemberNameQueryProcessor memberNameQueryProcessor;

    @Mock
    private AdminAuditLogAdminPresenter adminAuditLogAdminPresenter;

    @InjectMocks
    private AdminAuditLogAdminFacade facade;

    @Test
    @DisplayName("페이지 항목이 여러 개여도 이름 조회는 한 번이고, 모은 아이디만 넘긴다")
    void looksUpNamesOncePerPage() {
        Page<AdminAuditLog> logs = new PageImpl<>(List.of(
            new AdminAuditLog(1L, "ADMIN_LOGIN", 10L, "member:20", "SUCCESS", AT),
            new AdminAuditLog(2L, "MEMBER_SEARCH", 10L, "members?role=ALL&page=0&size=20", "SUCCESS", AT),
            new AdminAuditLog(3L, "ADMIN_LOGIN", null, "member:20", "FAILURE", AT)
        ));
        Map<Long, String> names = Map.of(10L, "홍길동", 20L, "김철수");
        PageResponse<AdminAuditLogAdminItem> response = new PageResponse<>(List.of(), 0, 20, 3L, 1);
        given(adminAuditLogQueryProcessor.search(COMMAND)).willReturn(logs);
        given(memberNameQueryProcessor.findNamesByIds(List.of(10L, 20L))).willReturn(names);
        given(adminAuditLogAdminPresenter.toPageResponse(logs, names)).willReturn(response);

        assertThat(facade.searchAuditLogs(COMMAND)).isSameAs(response);
        verify(memberNameQueryProcessor, times(1)).findNamesByIds(List.of(10L, 20L));
    }
}
