package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.application.port.out.query.MemberNameQueryResult;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회원 이름을 아이디 목록으로 한 번에 읽는다 (#322). 응답으로 바꾸거나 마스킹하지 않는다 — 그건 Presenter 다.
 * 트랜잭션은 부른 Facade 가 건다.
 */
@Component
@RequiredArgsConstructor
public class MemberNameQueryProcessor {

    private final MemberRepositoryPort memberRepositoryPort;

    /**
     * @param memberIds 중복이 있어도 조회는 한 번이다. 빈 목록이면 저장소를 부르지 않는다
     * @return 있는 회원만. 이름 원문이고, 없으면 null. 없는 아이디는 키도 없다
     */
    public Map<Long, String> findNamesByIds(Collection<Long> memberIds) {
        List<Long> distinctIds = memberIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (MemberNameQueryResult row : memberRepositoryPort.findNamesByIds(distinctIds)) {
            names.putIfAbsent(row.memberId(), row.name());
        }
        return names;
    }
}
