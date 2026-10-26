package com.commercefoundry.domainlayer.member.application.port.in;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MemberDisplayNameInternalResponse;
import java.util.List;

public interface MemberDisplayNameInternalUseCase {

    /**
     * 서비스 간 회원 표시 이름 벌크 조회 ({@code api-design-guide.md} §1-3, #322). 중복은 첫 등장 순서로 제거하고, 없는 아이디는 응답에서 뺀다.
     * 이름은 마스킹된 값만 나간다.
     *
     * @param memberIds 검증을 통과한 1~100개의 양수 아이디
     */
    MemberDisplayNameInternalResponse getDisplayNames(List<Long> memberIds);
}
