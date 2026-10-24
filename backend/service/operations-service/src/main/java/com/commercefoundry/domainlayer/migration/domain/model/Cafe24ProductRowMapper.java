package com.commercefoundry.domainlayer.migration.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 카페24 상품 원본 행을 정규화한 기본 칸 레코드로 바꾼다. 칸마다 규칙({@link MigrationFieldNormalizer})을 적용하고 실패를 모두 모은다 —
 * 첫 실패에서 멈추지 않아 운영자가 한 번에 고칠 수 있다. 거절 이슈가 하나라도 있으면 레코드가 없다(그 행은 이관하지 않는다).
 */
public final class Cafe24ProductRowMapper {

    private final Cafe24ProductColumns columns;

    public Cafe24ProductRowMapper(Cafe24ProductColumns columns) {
        this.columns = Objects.requireNonNull(columns, "columns");
    }

    public Mapped map(SourceRow row) {
        List<MigrationIssue> issues = new ArrayList<>();
        String sourceKey = blankToNull(row.cell(columns.productCode()));
        if (sourceKey == null) {
            issues.add(new MigrationIssue(row.rowNumber(), null, "sourceKey", MigrationIssueCode.SOURCE_KEY_REQUIRED));
        }
        Normalized<String> name = collect(MigrationFieldNormalizer.productName(row.cell(columns.name())), row, sourceKey, "name", issues);
        Normalized<Long> salePrice = collect(MigrationFieldNormalizer.krwAmount(row.cell(columns.salePrice())), row, sourceKey, "salePrice", issues);
        Normalized<Long> listPrice = collect(MigrationFieldNormalizer.optionalKrwAmount(row.cell(columns.listPrice())), row, sourceKey, "listPrice", issues);
        Normalized<Integer> weight = collect(
            MigrationFieldNormalizer.weightGrams(row.cell(columns.weight()), columns.weightUnit()), row, sourceKey, "weightGrams", issues
        );
        Normalized<String> hsCode = collect(MigrationFieldNormalizer.hsCode(row.cell(columns.hsCode())), row, sourceKey, "hsCode", issues);

        if (issues.stream().anyMatch(issue -> issue.code().rejects())) {
            return new Mapped(null, issues);
        }
        return new Mapped(
            new SourceProductRecord(row.rowNumber(), sourceKey, name.value(), salePrice.value(), listPrice.value(), weight.value(), hsCode.value()),
            issues
        );
    }

    private static <T> Normalized<T> collect(Normalized<T> result, SourceRow row, String sourceKey, String field, List<MigrationIssue> issues) {
        if (result.failed()) {
            issues.add(new MigrationIssue(row.rowNumber(), sourceKey, field, result.issue()));
        }
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * @param record 거절 이슈가 있으면 null
     * @param issues 거절 · 경고 모두
     */
    public record Mapped(SourceProductRecord record, List<MigrationIssue> issues) {

        public Mapped {
            issues = List.copyOf(issues);
        }
    }
}
