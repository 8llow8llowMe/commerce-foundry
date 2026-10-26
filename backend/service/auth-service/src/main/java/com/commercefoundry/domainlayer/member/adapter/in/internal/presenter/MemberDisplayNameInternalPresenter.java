package com.commercefoundry.domainlayer.member.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.item.MemberDisplayNameInternalItem;
import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MemberDisplayNameInternalResponse;
import com.commercefoundry.domainlayer.member.adapter.in.support.MemberMasking;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 회원 아이디를 문자열로 바꾸고 이름을 마스킹한다 (#322). 원문 이름은 이 응답 밖으로 나가지 않는다. */
@Component
public class MemberDisplayNameInternalPresenter {

    /**
     * @param requestedIds 요청 순서 그대로. 중복은 첫 등장만 남긴다
     * @param namesByMemberId 있는 회원의 이름 원문. 없는 아이디는 키가 없고, 이름이 없으면 값이 null
     */
    public MemberDisplayNameInternalResponse toResponse(List<Long> requestedIds, Map<Long, String> namesByMemberId) {
        List<MemberDisplayNameInternalItem> members = new ArrayList<>();
        for (Long memberId : requestedIds.stream().distinct().toList()) {
            if (!namesByMemberId.containsKey(memberId)) {
                continue;
            }
            members.add(MemberDisplayNameInternalItem.builder()
                .memberId(String.valueOf(memberId))
                .displayName(MemberMasking.maskNameIfPresent(namesByMemberId.get(memberId)))
                .build());
        }
        return MemberDisplayNameInternalResponse.builder().members(List.copyOf(members)).build();
    }
}
