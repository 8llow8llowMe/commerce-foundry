package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.info.AdminMemberInfo;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.query.MemberListQueryResult;
import com.commercefoundry.security.principal.MemberRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 회원 조회 (#6). 목록 행({@link MemberListQueryResult})을 Info 로 옮긴다 — QueryResult 가 Facade · Presenter 로 번지지 않게 ({@code architecture-guide.md} §4).
 */
@Component
@RequiredArgsConstructor
public class MemberAdminProcessor {

    private final MemberRepositoryPort memberRepositoryPort;

    /**
     * 최신순(아이디 내림차순) 한 페이지. 쿼리는 목록 한 번 · count 한 번이다.
     *
     * @param role null 이면 전체
     */
    @Transactional(readOnly = true)
    public Page<AdminMemberInfo> search(MemberRole role, int page, int size) {
        return memberRepositoryPort.findPage(role, page, size).map(MemberAdminProcessor::toInfo);
    }

    private static AdminMemberInfo toInfo(MemberListQueryResult row) {
        return new AdminMemberInfo(row.memberId(), row.email(), row.name(), row.phone(), row.role(), row.createdAt());
    }
}
