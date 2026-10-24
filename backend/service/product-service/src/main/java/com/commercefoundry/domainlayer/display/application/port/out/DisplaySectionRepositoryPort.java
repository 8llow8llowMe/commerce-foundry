package com.commercefoundry.domainlayer.display.application.port.out;

import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DisplaySectionRepositoryPort {

    /** 숨김 · 기간 밖 포함 전체 섹션 — 노출 순서 → 아이디 오름차순. 섹션 수에 상한(50)이 있어 페이지 없이 읽는다. 아이템은 읽지 않는다. */
    List<DisplaySection> findAll();

    Optional<DisplaySection> findById(long sectionId);

    /**
     * 어드민 쓰기용 전체 섹션 — 모든 섹션 행에 쓰기 잠금을 건 뒤 다시 읽는다(노출 순서 → 아이디). 트랜잭션이 끝날 때까지 다른 어드민 전시 쓰기가 기다린다.
     * 트랜잭션 안에서 부른다.
     */
    List<DisplaySection> findAllForUpdate();

    /** 섹션 묶음의 아이템 전체 — 한 번(섹션 아이디 in 절)에 읽는다. 섹션 → 노출 순서 → 아이디 오름차순. 빈 컬렉션이면 쿼리 없이 빈 목록이다. */
    List<DisplayItem> findItemsBySectionIds(Collection<Long> sectionIds);

    /** 새 섹션과 아이템 INSERT. 아이디는 호출한 쪽이 발급했다. */
    void insert(DisplaySectionContent content);

    /**
     * 이미 있는 섹션의 칸을 바꾸고 아이템을 통째로 바꾼다 — 기존 아이템을 한 문장으로 지운 뒤 새 아이템을 넣는다. 섹션이 없으면 {@link IllegalStateException} 이다.
     */
    void replace(DisplaySectionContent content);

    /** 섹션과 그 아이템을 지운다. */
    void delete(long sectionId);

    /** 섹션 노출 순서를 바꾼다 — 아이디 → 새 순서. 없는 아이디가 있으면 {@link IllegalStateException} 이다. */
    void changeDisplayOrders(Map<Long, Integer> displayOrders);
}
