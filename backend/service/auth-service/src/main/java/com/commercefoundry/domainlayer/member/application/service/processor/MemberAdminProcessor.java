package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.info.AdminMemberInfo;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.query.MemberListQueryResult;
import com.commercefoundry.security.principal.MemberRole;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
     * <p>운영자 명단은 직원 조회 권한이 있어야 본다 (#236). {@code includeOperators} 가 false 면 역할 조건을 고객으로 고정하고,
     * 운영자 역할로 거르면 질의 없이 빈 페이지다 — 오류로 답하지 않아 운영자 계정이 있는지도 드러나지 않는다.
     *
     * @param includeOperators 운영자 계정 행까지 볼 수 있는가
     * @param role             null 이면 전체(볼 수 있는 범위 안에서)
     */
    @Transactional(readOnly = true)
    public Page<AdminMemberInfo> search(boolean includeOperators, MemberRole role, int page, int size) {
        if (includeOperators) {
            return memberRepositoryPort.findPage(role, page, size).map(MemberAdminProcessor::toInfo);
        }
        if (role != null && role.isOperator()) {
            return new PageImpl<>(List.of(), PageRequest.of(page, size), 0);
        }
        return memberRepositoryPort.findPage(MemberRole.CUSTOMER, page, size).map(MemberAdminProcessor::toInfo);
    }

    private static AdminMemberInfo toInfo(MemberListQueryResult row) {
        return new AdminMemberInfo(row.memberId(), row.email(), row.name(), row.phone(), row.role(), row.createdAt());
    }
}
