package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.info.AdminAccountInfo;
import com.commercefoundry.domainlayer.member.application.port.out.AdminOtpRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.query.OperatorAccountQueryResult;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 계정 목록 (#236). 목록 행({@link OperatorAccountQueryResult})을 Info 로 옮긴다 — QueryResult 가 Facade · Presenter 로 번지지 않게.
 * OTP 등록 여부는 페이지의 회원 아이디로 한 번에 읽는다(N+1 없음) — 질의는 목록 · count · OTP {@code in} 절 세 번이다.
 */
@Component
@RequiredArgsConstructor
public class AdminAccountQueryProcessor {

    private final MemberRepositoryPort memberRepositoryPort;
    private final AdminOtpRepositoryPort adminOtpRepositoryPort;

    /**
     * 운영자 역할 전체, 최신순(아이디 내림차순) 한 페이지.
     *
     * @param active true 면 활성만, false 면 비활성만, null 이면 전체
     */
    @Transactional(readOnly = true)
    public Page<AdminAccountInfo> search(Boolean active, int page, int size) {
        Page<OperatorAccountQueryResult> rows = memberRepositoryPort.findOperatorPage(active, page, size);
        Set<Long> enrolled = adminOtpRepositoryPort.findEnrolledMemberIds(rows.map(OperatorAccountQueryResult::memberId).getContent());
        return rows.map(row -> new AdminAccountInfo(
            row.memberId(), row.email(), row.name(), row.role(), row.deactivatedAt(), enrolled.contains(row.memberId()), row.createdAt()));
    }
}
