package com.commercefoundry.domainlayer.member.adapter.in.admin.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.in.admin.dto.item.AdminMemberItem;
import com.commercefoundry.domainlayer.member.application.info.AdminMemberInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import com.commercefoundry.security.principal.MemberRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class MemberAdminPresenterTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-18T03:00:00Z");

    private final MemberAdminPresenter presenter = new MemberAdminPresenter();

    @Test
    @DisplayName("아이디는 문자열, 이메일 · 이름 · 전화는 마스킹한 값이고 페이지 정보(자르기 전 건수)는 그대로다")
    void toPageResponse() {
        AdminMemberInfo info = new AdminMemberInfo(4611686018427387904L, "customer@example.com", "홍길동", "01012345678", MemberRole.CUSTOMER, CREATED_AT);

        PageResponse<AdminMemberItem> response = presenter.toPageResponse(new PageImpl<>(List.of(info), PageRequest.of(1, 1), 3));

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.contents()).singleElement().isEqualTo(AdminMemberItem.builder()
            .memberId("4611686018427387904").email("cu***@e***.com").name("홍*동").phone("010-****-5678").role(MemberRole.CUSTOMER).createdAt(CREATED_AT)
            .build());
    }

    @Test
    @DisplayName("전화가 없으면 null 이다")
    void noPhone() {
        AdminMemberInfo info = new AdminMemberInfo(1L, "admin@example.com", "관리자", null, MemberRole.ADMIN, CREATED_AT);

        assertThat(presenter.toPageResponse(new PageImpl<>(List.of(info))).contents()).singleElement().extracting(AdminMemberItem::phone).isNull();
    }
}
