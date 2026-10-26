package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartTemplateUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartTemplateDetailInfo;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartTemplateSummaryInfo;
import com.commercefoundry.domainlayer.sizechart.application.port.out.ProductSizeChartRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.application.port.out.SizeChartTemplateRepositoryPort;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사이즈표 템플릿 읽기 · 쓰기. 항목 수는 목록에 나온 템플릿 아이디 in 절 한 번으로 센다.
 *
 * <p>쓰기는 {@code MANDATORY} 다. Facade 트랜잭션 안에서 템플릿 행을 잠근 뒤 상품 표가 쓰는지 보고 고친다.
 * 쓰는 중이면 항목 아이디를 유지하고, 아니면 항목을 지우고 새 아이디로 넣는다.
 */
@Component
@RequiredArgsConstructor
public class SizeChartTemplateProcessor {

    private static final Comparator<SizeChartTemplateColumn> COLUMN_ORDER = Comparator
        .comparingInt(SizeChartTemplateColumn::displayOrder)
        .thenComparingLong(SizeChartTemplateColumn::id);

    private final SizeChartTemplateRepositoryPort sizeChartTemplateRepositoryPort;
    private final ProductSizeChartRepositoryPort productSizeChartRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public Page<SizeChartTemplateSummaryInfo> getTemplates(int page, int size) {
        Page<SizeChartTemplate> templates = sizeChartTemplateRepositoryPort.findPage(page, size);
        if (templates.isEmpty()) {
            return templates.map(template -> new SizeChartTemplateSummaryInfo(template.id(), template.name(), template.baseUnit(), 0));
        }
        List<Long> templateIds = templates.getContent().stream().map(SizeChartTemplate::id).toList();
        Map<Long, Integer> columnCounts = sizeChartTemplateRepositoryPort.countColumnsByTemplateIds(templateIds);
        return templates.map(template -> new SizeChartTemplateSummaryInfo(
            template.id(), template.name(), template.baseUnit(), columnCounts.getOrDefault(template.id(), 0)
        ));
    }

    public SizeChartTemplateDetailInfo getTemplate(long templateId) {
        SizeChartTemplate template = sizeChartTemplateRepositoryPort.findById(templateId)
            .orElseThrow(() -> new SizeChartException(SizeChartErrorCode.NOT_FOUND_TEMPLATE));
        return toDetail(template, sizeChartTemplateRepositoryPort.findColumns(templateId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SizeChartTemplateDetailInfo create(SizeChartTemplateUpsertCommand command) {
        requireDistinctColumns(command.columns());
        long templateId = snowflakeIdGenerator.generateId();
        List<SizeChartTemplateColumn> columns = newColumns(templateId, command.columns());
        SizeChartTemplate template = new SizeChartTemplate(templateId, command.name(), command.baseUnit());
        sizeChartTemplateRepositoryPort.insert(template, columns);
        return toDetail(template, columns);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SizeChartTemplateDetailInfo update(long templateId, SizeChartTemplateUpsertCommand command) {
        requireDistinctColumns(command.columns());
        SizeChartTemplate locked = sizeChartTemplateRepositoryPort.lockById(templateId)
            .orElseThrow(() -> new SizeChartException(SizeChartErrorCode.NOT_FOUND_TEMPLATE));
        SizeChartTemplate template = new SizeChartTemplate(locked.id(), command.name(), command.baseUnit());
        if (productSizeChartRepositoryPort.existsByTemplateId(templateId)) {
            List<SizeChartTemplateColumn> current = sizeChartTemplateRepositoryPort.findColumns(templateId);
            List<SizeChartTemplateColumn> updated = columnsKeepingIds(templateId, locked.baseUnit(), current, command);
            sizeChartTemplateRepositoryPort.updateColumns(template, updated);
            return toDetail(template, updated);
        }
        List<SizeChartTemplateColumn> columns = newColumns(templateId, command.columns());
        sizeChartTemplateRepositoryPort.replaceColumns(template, columns);
        return toDetail(template, columns);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(long templateId) {
        sizeChartTemplateRepositoryPort.lockById(templateId)
            .orElseThrow(() -> new SizeChartException(SizeChartErrorCode.NOT_FOUND_TEMPLATE));
        if (productSizeChartRepositoryPort.existsByTemplateId(templateId)) {
            throw new SizeChartException(SizeChartErrorCode.TEMPLATE_IN_USE);
        }
        sizeChartTemplateRepositoryPort.delete(templateId);
    }

    private List<SizeChartTemplateColumn> columnsKeepingIds(
        long templateId, SizeChartUnit currentBaseUnit, List<SizeChartTemplateColumn> current, SizeChartTemplateUpsertCommand command
    ) {
        if (command.baseUnit() != currentBaseUnit) {
            throw new SizeChartException(SizeChartErrorCode.TEMPLATE_STRUCTURE_LOCKED);
        }
        Set<Long> requested = new HashSet<>();
        for (SizeChartTemplateUpsertCommand.Column column : command.columns()) {
            if (column.columnId() == null) {
                throw new SizeChartException(SizeChartErrorCode.TEMPLATE_STRUCTURE_LOCKED);
            }
            if (!requested.add(column.columnId())) {
                throw new SizeChartException(SizeChartErrorCode.COLUMN_ID_DUPLICATED);
            }
        }
        Set<Long> existing = new HashSet<>();
        current.forEach(column -> existing.add(column.id()));
        if (!requested.equals(existing)) {
            throw new SizeChartException(SizeChartErrorCode.TEMPLATE_STRUCTURE_LOCKED);
        }
        List<SizeChartTemplateColumn> updated = new ArrayList<>(command.columns().size());
        for (SizeChartTemplateUpsertCommand.Column column : command.columns()) {
            updated.add(new SizeChartTemplateColumn(column.columnId(), templateId, column.name(), column.description(), column.displayOrder()));
        }
        return updated;
    }

    private List<SizeChartTemplateColumn> newColumns(long templateId, List<SizeChartTemplateUpsertCommand.Column> columns) {
        List<SizeChartTemplateColumn> created = new ArrayList<>(columns.size());
        for (SizeChartTemplateUpsertCommand.Column column : columns) {
            created.add(new SizeChartTemplateColumn(
                snowflakeIdGenerator.generateId(), templateId, column.name(), column.description(), column.displayOrder()
            ));
        }
        return created;
    }

    private static void requireDistinctColumns(List<SizeChartTemplateUpsertCommand.Column> columns) {
        Set<String> names = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (SizeChartTemplateUpsertCommand.Column column : columns) {
            if (!names.add(column.name())) {
                throw new SizeChartException(SizeChartErrorCode.COLUMN_NAME_DUPLICATED);
            }
            if (!orders.add(column.displayOrder())) {
                throw new SizeChartException(SizeChartErrorCode.COLUMN_ORDER_DUPLICATED);
            }
        }
    }

    private static SizeChartTemplateDetailInfo toDetail(SizeChartTemplate template, List<SizeChartTemplateColumn> columns) {
        List<SizeChartTemplateDetailInfo.Column> items = columns.stream()
            .sorted(COLUMN_ORDER)
            .map(column -> new SizeChartTemplateDetailInfo.Column(column.id(), column.name(), column.description(), column.displayOrder()))
            .toList();
        return new SizeChartTemplateDetailInfo(template.id(), template.name(), template.baseUnit(), items);
    }
}
