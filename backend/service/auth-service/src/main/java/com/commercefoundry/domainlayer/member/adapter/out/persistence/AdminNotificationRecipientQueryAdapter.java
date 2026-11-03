package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.MemberRepository;
import com.commercefoundry.domainlayer.member.application.port.out.AdminNotificationRecipientQueryPort;
import com.commercefoundry.security.principal.MemberRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminNotificationRecipientQueryAdapter implements AdminNotificationRecipientQueryPort {

    private final MemberRepository memberRepository;

    @Override
    public List<Long> findActiveAdminIdsAfter(long afterId, int size) {
        // List + limit: count 질의 · 전체 회원/개인정보 로딩 · 운영자별 단건 조회 없이 한 번 읽는다.
        return memberRepository.findByRoleAndDeactivatedAtIsNullAndIdGreaterThanOrderByIdAsc(MemberRole.ADMIN, afterId, PageRequest.of(0, size))
            .stream().map(MemberRepository.AdminRecipientIdView::getId).toList();
    }
}
