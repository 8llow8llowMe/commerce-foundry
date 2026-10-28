package com.commercefoundry.domainlayer.look.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.look.adapter.out.persistence.entity.LookItemEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LookItemRepository extends JpaRepository<LookItemEntity, Long> {

    /** 룩 묶음의 아이템 — 룩 아이디 in 절 한 번. {@code uk_look_item_look_id_display_order} 선두 칸을 탄다. */
    List<LookItemEntity> findAllByLookIdInOrderByLookIdAscDisplayOrderAscIdAsc(Collection<Long> lookIds);

    /**
     * 룩의 아이템을 한 문장으로 지운다. 파생 {@code deleteBy...} 는 행마다 읽어 {@code remove} 하고, Hibernate 는 같은 flush 에서 INSERT 를 DELETE 보다 먼저 내보내
     * 새 아이템이 유니크 키에 걸린다 — 그래서 bulk DELETE 를 바로 실행한다. 앞서 쌓인 쓰기(룩 칸 변경)는 먼저 내보낸다(선례: {@code DisplayItemRepository}).
     * 이 트랜잭션에서 아이템 엔티티를 읽지 않으므로 영속성 컨텍스트를 비우지 않는다({@code clearAutomatically} 미사용) — 잠근 룩 엔티티가 관리 상태로 남는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from LookItemEntity item where item.lookId = :lookId")
    int deleteAllByLookIdInBulk(long lookId);
}
