package com.commercefoundry.domainlayer.sizechart.adapter.out.persistence;

import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartTemplateColumnEntity;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.entity.SizeChartTemplateEntity;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartTemplateColumnRepository;
import com.commercefoundry.domainlayer.sizechart.adapter.out.persistence.repository.SizeChartTemplateRepository;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SizeChartTemplateRepositoryAdapter implements SizeChartTemplateRepositoryPort {

    private final SizeChartTemplateRepository sizeChartTemplateRepository;
    private final SizeChartTemplateColumnRepository sizeChartTemplateColumnRepository;

    @Override
    public Optional<SizeChartTemplate> findById(long templateId) {
        return sizeChartTemplateRepository.findById(templateId).map(this::toDomain);
    }

    @Override
    public Optional<SizeChartTemplate> lockById(long templateId) {
        return sizeChartTemplateRepository.findByIdForUpdate(templateId).map(this::toDomain);
    }

    @Override
    public Page<SizeChartTemplate> findPage(int page, int size) {
        return sizeChartTemplateRepository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"))).map(this::toDomain);
    }

    @Override
    public Map<Long, Integer> countColumnsByTemplateIds(Collection<Long> templateIds) {
        if (templateIds.isEmpty()) {
            return Map.of();
        }
        return sizeChartTemplateColumnRepository.countByTemplateIds(templateIds).stream()
            .collect(Collectors.toMap(TemplateColumnCountRow::templateId, row -> Math.toIntExact(row.columnCount())));
    }

    @Override
    public List<SizeChartTemplateColumn> findColumns(long templateId) {
        return sizeChartTemplateColumnRepository.findByTemplateIdOrderByDisplayOrderAscIdAsc(templateId).stream().map(this::toDomain).toList();
    }

    @Override
    public void insert(SizeChartTemplate template, List<SizeChartTemplateColumn> columns) {
        sizeChartTemplateRepository.save(toEntity(template));
        sizeChartTemplateColumnRepository.saveAll(columns.stream().map(this::toEntity).toList());
        sizeChartTemplateRepository.flush();
    }

    @Override
    public void updateColumns(SizeChartTemplate template, List<SizeChartTemplateColumn> columns) {
        Map<Long, SizeChartTemplateColumnEntity> entities = sizeChartTemplateColumnRepository.findByTemplateIdOrderByDisplayOrderAscIdAsc(template.id()).stream()
            .collect(Collectors.toMap(SizeChartTemplateColumnEntity::getId, Function.identity()));
        entities.values().forEach(SizeChartTemplateColumnEntity::stageTemporaryName);
        sizeChartTemplateColumnRepository.flush();
        SizeChartTemplateEntity head = sizeChartTemplateRepository.findById(template.id())
            .orElseThrow(() -> new IllegalStateException("수정할 사이즈표 템플릿이 없습니다. templateId=" + template.id()));
        head.change(template.name(), template.baseUnit());
        for (SizeChartTemplateColumn column : columns) {
            SizeChartTemplateColumnEntity entity = entities.get(column.id());
            if (entity == null) {
                throw new IllegalStateException("수정할 템플릿 항목이 없습니다. columnId=" + column.id());
            }
            entity.change(column.name(), column.description(), column.displayOrder());
        }
        sizeChartTemplateRepository.flush();
    }

    @Override
    public void replaceColumns(SizeChartTemplate template, List<SizeChartTemplateColumn> columns) {
        sizeChartTemplateColumnRepository.deleteByTemplateId(template.id());
        SizeChartTemplateEntity head = sizeChartTemplateRepository.findById(template.id())
            .orElseThrow(() -> new IllegalStateException("수정할 사이즈표 템플릿이 없습니다. templateId=" + template.id()));
        head.change(template.name(), template.baseUnit());
        sizeChartTemplateColumnRepository.saveAll(columns.stream().map(this::toEntity).toList());
        sizeChartTemplateRepository.flush();
    }

    @Override
    public void delete(long templateId) {
        sizeChartTemplateColumnRepository.deleteByTemplateId(templateId);
        sizeChartTemplateRepository.deleteById(templateId);
        sizeChartTemplateRepository.flush();
    }

    private SizeChartTemplate toDomain(SizeChartTemplateEntity entity) {
        return new SizeChartTemplate(entity.getId(), entity.getName(), entity.getBaseUnit());
    }

    private SizeChartTemplateColumn toDomain(SizeChartTemplateColumnEntity entity) {
        return new SizeChartTemplateColumn(entity.getId(), entity.getTemplateId(), entity.getName(), entity.getDescription(), entity.getDisplayOrder());
    }

    private SizeChartTemplateEntity toEntity(SizeChartTemplate template) {
        return SizeChartTemplateEntity.builder().id(template.id()).name(template.name()).baseUnit(template.baseUnit()).build();
    }

    private SizeChartTemplateColumnEntity toEntity(SizeChartTemplateColumn column) {
        return SizeChartTemplateColumnEntity.builder()
            .id(column.id())
            .templateId(column.templateId())
            .name(column.name())
            .description(column.description())
            .displayOrder(column.displayOrder())
            .build();
    }
}
