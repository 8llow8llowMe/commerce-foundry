package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.ProductSizeRowEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductSizeRowRepository extends JpaRepository<ProductSizeRowEntity, Long> {

    List<ProductSizeRowEntity> findBySizeChartIdOrderByDisplayOrderAscIdAsc(long sizeChartId);

    /** 행을 한 문장으로 지운다. 칸을 먼저 지운 뒤에 부른다. 이 트랜잭션에서 행을 읽어 두지 않는다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from ProductSizeRowEntity row where row.sizeChartId = :sizeChartId")
    int deleteBySizeChartId(long sizeChartId);
}
