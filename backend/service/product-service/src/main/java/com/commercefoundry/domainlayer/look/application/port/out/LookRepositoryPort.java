package com.commercefoundry.domainlayer.look.application.port.out;

import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface LookRepositoryPort {

    /** 숨긴 룩 포함 전체 — 노출 순서 → 아이디 오름차순. 룩 수에 상한(100)이 있어 페이지 없이 읽는다. 아이템은 읽지 않는다. */
    List<Look> findAll();

    Optional<Look> findById(long lookId);

    /**
     * 어드민 쓰기용 전체 룩 — 모든 룩 행에 쓰기 잠금을 건 뒤 다시 읽는다(노출 순서 → 아이디). 트랜잭션이 끝날 때까지 다른 어드민 룩 쓰기가 기다린다.
     * 트랜잭션 안에서 부른다.
     */
    List<Look> findAllForUpdate();

    /** 룩 묶음의 아이템 전체 — 한 번(룩 아이디 in 절)에 읽는다. 룩 → 노출 순서 → 아이디 오름차순. 빈 컬렉션이면 쿼리 없이 빈 목록이다. */
    List<LookItem> findItemsByLookIds(Collection<Long> lookIds);

    /** 새 룩과 아이템 INSERT. 아이디는 호출한 쪽이 발급했다. */
    void insert(LookContent content);

    /** 이미 있는 룩의 칸을 바꾸고 아이템을 통째로 바꾼다 — 기존 아이템을 한 문장으로 지운 뒤 새 아이템을 넣는다. 룩이 없으면 {@link IllegalStateException} 이다. */
    void replace(LookContent content);

    /** 룩과 그 아이템을 지운다. */
    void delete(long lookId);

    /** 룩 노출 순서를 바꾼다 — 아이디 → 새 순서. 없는 아이디가 있으면 {@link IllegalStateException} 이다. */
    void changeDisplayOrders(Map<Long, Integer> displayOrders);
}
