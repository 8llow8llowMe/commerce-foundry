package com.commercefoundry.domainlayer.migration.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 카페24 상품 행 → 기본 칸 레코드. 컬럼명은 설정값이라 테스트도 임의 이름을 쓴다 — 실제 카페24 컬럼명(D3 확인 전)을 가정하지 않는다. 값은 모두 합성이다.
 */
class Cafe24ProductRowMapperTest {

    private static final Cafe24ProductColumns COLUMNS = new Cafe24ProductColumns("code", "name", "price", "listPrice", "weight", WeightUnit.KILOGRAM, "hs");
    private final Cafe24ProductRowMapper mapper = new Cafe24ProductRowMapper(COLUMNS);

    private static SourceRow row(int rowNumber, Map<String, String> cells) {
        return new SourceRow(rowNumber, cells);
    }

    @Test
    @DisplayName("규칙을 지킨 행은 레코드가 되고 이슈가 없다")
    void mapsValidRow() {
        Cafe24ProductRowMapper.Mapped mapped = mapper.map(row(1, Map.of(
            "code", " P00000001 ", "name", "합성 상품 0001", "price", "39,000", "listPrice", "45000", "weight", "0.35", "hs", "6211.43"
        )));

        assertThat(mapped.issues()).isEmpty();
        assertThat(mapped.record()).isEqualTo(new SourceProductRecord(1, "P00000001", "합성 상품 0001", 39_000L, 45_000L, 350, "621143"));
    }

    @Test
    @DisplayName("선택 칸이 비었거나 컬럼이 없으면 비운다")
    void optionalColumnsMayBeAbsent() {
        Cafe24ProductRowMapper.Mapped mapped = new Cafe24ProductRowMapper(
            new Cafe24ProductColumns("code", "name", "price", null, null, WeightUnit.GRAM, null)
        ).map(row(2, Map.of("code", "P00000002", "name", "합성 상품 0002", "price", "1000")));

        assertThat(mapped.issues()).isEmpty();
        assertThat(mapped.record().listPrice()).isNull();
        assertThat(mapped.record().weightGrams()).isNull();
        assertThat(mapped.record().hsCode()).isNull();
    }

    @Test
    @DisplayName("경고만 있으면 그 칸을 비우고 레코드를 만든다")
    void warningsKeepRecord() {
        Cafe24ProductRowMapper.Mapped mapped = mapper.map(row(3, Map.of(
            "code", "P00000003", "name", "합성 상품 0003", "price", "1000", "weight", "0", "hs", "HS"
        )));

        assertThat(mapped.record()).isNotNull();
        assertThat(mapped.record().weightGrams()).isNull();
        assertThat(mapped.record().hsCode()).isNull();
        assertThat(mapped.issues()).extracting(MigrationIssue::field, MigrationIssue::code)
            .containsExactlyInAnyOrder(
                tuple("weightGrams", MigrationIssueCode.WEIGHT_INVALID),
                tuple("hsCode", MigrationIssueCode.HS_CODE_INVALID)
            );
    }

    @Test
    @DisplayName("거절 이슈가 있으면 레코드가 없고, 첫 실패에서 멈추지 않고 모든 칸의 이슈를 모은다 — 이슈에는 값이 없다")
    void collectsAllIssues() {
        Map<String, String> cells = new HashMap<>();
        cells.put("code", "P00000004");
        cells.put("name", "가".repeat(101));
        cells.put("price", "-1");
        cells.put("hs", "1");
        Cafe24ProductRowMapper.Mapped mapped = mapper.map(row(4, cells));

        assertThat(mapped.record()).isNull();
        assertThat(mapped.issues()).extracting(MigrationIssue::code)
            .containsExactlyInAnyOrder(MigrationIssueCode.NAME_TOO_LONG, MigrationIssueCode.PRICE_INVALID, MigrationIssueCode.HS_CODE_INVALID);
        assertThat(mapped.issues()).allSatisfy(issue -> {
            assertThat(issue.rowNumber()).isEqualTo(4);
            assertThat(issue.sourceKey()).isEqualTo("P00000004");
        });
        assertThat(mapped.issues().toString()).doesNotContain("가가", "-1");
    }

    @Test
    @DisplayName("상품코드가 없으면 SOURCE_KEY_REQUIRED 이고 이슈의 원본 키는 null 이다")
    void requiresSourceKey() {
        Cafe24ProductRowMapper.Mapped mapped = mapper.map(row(5, Map.of("code", " ", "name", "합성 상품 0005", "price", "1000")));

        assertThat(mapped.record()).isNull();
        assertThat(mapped.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo(MigrationIssueCode.SOURCE_KEY_REQUIRED);
            assertThat(issue.sourceKey()).isNull();
        });
    }

    @Test
    @DisplayName("원본 행 · 레코드의 toString 은 값을 내지 않는다")
    void toStringHasNoValues() {
        SourceRow source = row(6, Map.of("name", "member0006@example.com"));
        assertThat(source.toString()).doesNotContain("example.com");
        assertThat(new SourceProductRecord(6, "P00000006", "합성 상품 0006", 1L, null, null, null).toString()).doesNotContain("합성 상품");
    }
}
