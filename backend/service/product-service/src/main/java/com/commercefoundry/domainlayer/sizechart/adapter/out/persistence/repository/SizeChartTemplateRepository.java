package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartTemplateEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface SizeChartTemplateRepository extends JpaRepository<SizeChartTemplateEntity, Long> {

    /**
     * 어드민 쓰기가 같은 템플릿에서 줄을 서게 한다. 없는 아이디는 빈 값이고 잠글 행이 없다.
     * 잠금 대기는 {@link #LOCK_TIMEOUT_MILLIS} 까지다. 넘으면 {@code PessimisticLockingFailureException} 이 나고 Facade 가 409 로 바꾼다.
     */
    String LOCK_TIMEOUT_MILLIS = "2000";

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = LOCK_TIMEOUT_MILLIS))
    @Query("select template from SizeChartTemplateEntity template where template.id = :templateId")
    Optional<SizeChartTemplateEntity> findByIdForUpdate(long templateId);
}
