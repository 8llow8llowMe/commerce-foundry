package com.commercefoundry.domainlayer.display.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.display.adapter.out.persistence.entity.DisplaySectionEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface DisplaySectionRepository extends JpaRepository<DisplaySectionEntity, Long> {

    /** 노출 순서 → 아이디 오름차순. 섹션 수에 상한(50)이 있어 전체를 읽는다. */
    List<DisplaySectionEntity> findAllByOrderByDisplayOrderAscIdAsc();

    /**
     * 어드민 쓰기 직렬화용 — 모든 섹션 행에 쓰기 잠금을 건다. 아이디 오름차순으로 잠가 두 쓰기가 서로 다른 순서로 잠그며 교착하지 않게 한다.
     * 잠금 대기 상한은 호출한 Facade 의 트랜잭션 시간 상한이 쿼리 타임아웃으로 건다(어드민 카테고리와 같다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<DisplaySectionEntity> findAllForUpdateByOrderByIdAsc();
}
