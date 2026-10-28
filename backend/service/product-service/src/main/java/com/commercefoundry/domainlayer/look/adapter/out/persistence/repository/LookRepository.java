package com.commercefoundry.domainlayer.look.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.look.adapter.out.persistence.entity.LookEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
