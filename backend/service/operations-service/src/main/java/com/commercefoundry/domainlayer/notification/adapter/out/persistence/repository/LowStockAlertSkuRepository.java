package com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.LowStockAlertSkuEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LowStockAlertSkuRepository extends JpaRepository<LowStockAlertSkuEntity, Long> {
    /**
     * 추적 행 전체를 두 칸만 읽는다. 엔티티로 올리지 않아 영속성 컨텍스트 · dirty checking 스냅숏이 생기지 않는다.
     * 바꿀 행만 {@link #findAllById} 로 엔티티를 읽는다.
     */
    @Query("select s.skuId as skuId, s.missingSince as missingSince from LowStockAlertSkuEntity s order by s.skuId")
    List<TrackedRow> findAllTrackedRows();

    interface TrackedRow {
        Long getSkuId();

        Instant getMissingSince();
    }
}
