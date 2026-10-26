package com.commercefoundry.domainlayer.sizechart.application.port.out;

import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeCell;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeChart;
import com.commercefoundry.domainlayer.sizechart.domain.model.ProductSizeRow;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 상품 사이즈표 저장. 칸은 행 아이디 in 절 한 번으로만 읽는다. */
public interface ProductSizeChartRepositoryPort {

    Optional<ProductSizeChart> findByProductId(long productId);

    boolean existsByTemplateId(long templateId);

    /** 노출 순서 → 아이디 오름차순. */
    List<ProductSizeRow> findRowsByChartId(long sizeChartId);

    /** 빈 컬렉션이면 쿼리 없이 빈 목록이다. */
    List<ProductSizeCell> findCellsByRowIds(Collection<Long> rowIds);

    /**
     * 상품의 표를 통째로 바꾼다. 없으면 만들고, 있으면 머리만 고친 뒤 행 · 칸을 한 문장으로 지우고 다시 넣는다.
     * 호출 전에 템플릿 행을 같은 트랜잭션에서 잠가 둔다 — 이 메서드는 그다음 상품 표 행을 잠근다.
     */
    void replace(ProductSizeChart chart, List<ProductSizeRow> rows, List<ProductSizeCell> cells);
}
