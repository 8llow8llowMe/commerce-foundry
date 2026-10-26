package com.commercefoundry.domainlayer.sizechart.application.port.out;

import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;

/** 사이즈표 템플릿 저장. 항목 조회는 템플릿 아이디 in 절 · 단건 조건으로만 한다. */
public interface SizeChartTemplateRepositoryPort {

    Optional<SizeChartTemplate> findById(long templateId);

    /** 어드민 쓰기 직렬화. 이 트랜잭션이 끝날 때까지 같은 템플릿의 다른 쓰기가 기다린다. 없으면 빈 값. */
    Optional<SizeChartTemplate> lockById(long templateId);

    /** 아이디 내림차순. */
    Page<SizeChartTemplate> findPage(int page, int size);

    /**
     * 템플릿별 항목 수. 빈 컬렉션이면 쿼리 없이 빈 맵이다. 항목이 없는 템플릿은 맵에 없다 — 호출한 쪽이 0 으로 본다.
     */
    Map<Long, Integer> countColumnsByTemplateIds(Collection<Long> templateIds);

    /** 노출 순서 → 아이디 오름차순. */
    List<SizeChartTemplateColumn> findColumns(long templateId);

    void insert(SizeChartTemplate template, List<SizeChartTemplateColumn> columns);

    /**
     * 쓰는 중인 템플릿. 항목 아이디는 유지하고 이름 · 설명 · 순서만 바꾼다. 이름 유니크를 맞바꾸려면 임시 이름으로 한 번 밀고 다시 쓴다.
     * 호출 전에 {@link #lockById} 로 같은 트랜잭션에서 잠가 둔다.
     */
    void updateColumns(SizeChartTemplate template, List<SizeChartTemplateColumn> columns);

    /**
     * 아무도 안 쓰는 템플릿. 항목을 지우고 새 아이디로 다시 넣는다.
     * 호출 전에 {@link #lockById} 로 같은 트랜잭션에서 잠가 두고, 항목 엔티티를 읽어 두지 않는다.
     */
    void replaceColumns(SizeChartTemplate template, List<SizeChartTemplateColumn> columns);

    /** 항목을 지운 뒤 템플릿을 지운다. 호출 전에 잠가 둔다. */
    void delete(long templateId);
}
