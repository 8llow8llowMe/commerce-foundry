package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.TemplateColumnCountRow;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartTemplateColumnEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SizeChartTemplateColumnRepository extends JpaRepository<SizeChartTemplateColumnEntity, Long> {

    List<SizeChartTemplateColumnEntity> findByTemplateIdOrderByDisplayOrderAscIdAsc(long templateId);

    @Query("""
        select new com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.TemplateColumnCountRow(column.templateId, count(column.id))
        from SizeChartTemplateColumnEntity column
        where column.templateId in :templateIds
        group by column.templateId
        """)
    List<TemplateColumnCountRow> countByTemplateIds(Collection<Long> templateIds);

    /**
     * 템플릿 항목을 한 문장으로 지운다. 파생 delete 는 행마다 SELECT 한 뒤 같은 flush 에서 INSERT 가 DELETE 보다 먼저 나가 이름 유니크에 걸린다.
     * 이 트랜잭션에서 항목을 읽어 두지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from SizeChartTemplateColumnEntity column where column.templateId = :templateId")
    int deleteByTemplateId(long templateId);
}
