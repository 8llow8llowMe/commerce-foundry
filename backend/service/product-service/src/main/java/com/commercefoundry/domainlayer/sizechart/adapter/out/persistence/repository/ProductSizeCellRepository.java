package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.ProductSizeCellEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductSizeCellRepository extends JpaRepository<ProductSizeCellEntity, Long> {

    List<ProductSizeCellEntity> findByRowIdIn(Collection<Long> rowIds);

    /**
     * 표의 칸을 한 문장으로 지운다. 행 아이디를 먼저 읽어 루프에서 지우지 않는다.
     * 하위 쿼리라 행이 없어도 0건이다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        delete from ProductSizeCellEntity cell
        where cell.rowId in (select row.id from ProductSizeRowEntity row where row.sizeChartId = :sizeChartId)
        """)
    int deleteBySizeChartId(long sizeChartId);
}
