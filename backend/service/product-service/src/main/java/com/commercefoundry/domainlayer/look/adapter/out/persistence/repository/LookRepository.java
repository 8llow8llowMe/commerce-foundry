package com.commercefoundry.domainlayer.look.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.look.adapter.out.persistence.entity.LookEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface LookRepository extends JpaRepository<LookEntity, Long> {

    /**
     * 잠금 대기 상한(밀리초). 넘으면 {@code PessimisticLockingFailureException} 이 나고 Facade 가 409 {@code LOOK_008} 로 바꾼다.
     * 쓰기 트랜잭션 시간 상한(5초)보다 짧게 두어, 잠금을 못 얻은 쓰기가 남은 시간 안에 실패를 돌려준다(선례: {@code SizeChartTemplateRepository}).
     */
    String LOCK_TIMEOUT_MILLIS = "2000";

    /** 노출 순서 → 아이디 오름차순. 룩 수에 상한(100)이 있어 전체를 읽는다. */
    List<LookEntity> findAllByOrderByDisplayOrderAscIdAsc();

    /**
     * 어드민 쓰기 직렬화용 — 모든 룩 행에 쓰기 잠금을 건다. 아이디 오름차순으로 잠가 두 쓰기가 서로 다른 순서로 잠그며 교착하지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    List<LookEntity> findAllForUpdateByOrderByIdAsc();

    /** 고객 룩 목록 — 보이는 룩 한 쪽. 정렬은 호출한 쪽이 {@link Pageable} 에 싣는다(노출 순서 → 아이디). */
    Slice<LookEntity> findAllByVisibleTrue(Pageable pageable);

    /**
     * 상품이 든 <b>보이는</b> 룩 — 노출 순서 → 아이디 오름차순. {@code idx_look_item_product_id_look_id} 로 상품의 아이템을 찾고 룩 행과 맞춘다.
     * 한 룩에 같은 상품은 한 번뿐이라(유니크 키) 중복이 없다. 룩 수 상한(100)이 있어 페이지 없이 읽는다.
     */
    @Query("""
        select look from LookEntity look
        join LookItemEntity item on item.lookId = look.id
        where item.productId = :productId and look.visible = true
        order by look.displayOrder asc, look.id asc
        """)
    List<LookEntity> findVisibleByProductId(long productId);
}
