package com.commercefoundry.domainlayer.migration.adapter.out.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.migration.application.exception.MigrationErrorCode;
import com.commercefoundry.domainlayer.migration.application.exception.MigrationException;
import com.commercefoundry.domainlayer.migration.domain.model.SourceRow;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 내보내기 CSV 읽기. 픽스처는 파일이 아니라 text block 의 합성 값이다 — 실데이터 · 실데이터에서 뽑은 샘플을 쓰지 않는다({@code migration-guide.md} §6).
 * 컬럼명도 실제 카페24 이름을 가정하지 않는다.
 */
class CsvMigrationSourceReaderTest {

    private static final Charset CP949 = Charset.forName("MS949");

    private final CsvMigrationSourceReader reader = new CsvMigrationSourceReader();

    private List<SourceRow> read(String csv, Charset charset, List<String> required) {
        return reader.read(new ByteArrayInputStream(csv.getBytes(charset)), charset, required);
    }

    @Test
    @DisplayName("첫 행은 머리글, 나머지는 번호 1부터의 데이터 행이다 — 따옴표 안 쉼표 · 줄바꿈 · 겹따옴표를 지키고 빈 행은 건너뛴다")
    void readsRfc4180() {
        String csv = """
            code,name,description
            P00000001,"합성 상품, 0001","첫 줄
            둘째 줄 ""강조""\"

            P00000002,합성 상품 0002,
            """;

        List<SourceRow> rows = read(csv, StandardCharsets.UTF_8, List.of("code", "name"));

        assertThat(rows).extracting(SourceRow::rowNumber).containsExactly(1, 2);
        assertThat(rows.get(0).cell("name")).isEqualTo("합성 상품, 0001");
        assertThat(rows.get(0).cell("description")).isEqualTo("첫 줄\n둘째 줄 \"강조\"");
        assertThat(rows.get(1).cell("description")).isEmpty();
    }

    @Test
    @DisplayName("UTF-8 BOM 은 첫 컬럼명에 붙지 않는다")
    void stripsByteOrderMark() {
        String byteOrderMark = Character.toString(0xFEFF);
        List<SourceRow> rows = read(byteOrderMark + "code,name\nP00000001,합성 상품 0001\n", StandardCharsets.UTF_8, List.of("code"));

        assertThat(rows).singleElement().satisfies(row -> assertThat(row.cell("code")).isEqualTo("P00000001"));
    }

    @Test
    @DisplayName("설정한 인코딩(CP949)으로 읽는다")
    void readsCp949() {
        List<SourceRow> rows = read("상품코드,상품명\nP00000001,합성 상품 0001\n", CP949, List.of("상품코드", "상품명"));

        assertThat(rows).singleElement().satisfies(row -> assertThat(row.cell("상품명")).isEqualTo("합성 상품 0001"));
    }

    @Test
    @DisplayName("칸이 모자란 행은 모자란 칸이 없다(null)")
    void shortRowHasMissingCells() {
        List<SourceRow> rows = read("code,name,price\nP00000001,합성 상품 0001\n", StandardCharsets.UTF_8, List.of("code"));

        assertThat(rows.get(0).cell("price")).isNull();
    }

    @Test
    @DisplayName("필수 컬럼이 없으면 SOURCE_COLUMN_MISSING")
    void missingRequiredColumn() {
        assertThatThrownBy(() -> read("code,name\nP00000001,x\n", StandardCharsets.UTF_8, List.of("code", "price")))
            .isInstanceOfSatisfying(MigrationException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(MigrationErrorCode.SOURCE_COLUMN_MISSING));
    }

    @Test
    @DisplayName("같은 컬럼명이 둘이거나 형식이 깨지면 SOURCE_UNREADABLE 이고 메시지에 셀 값이 없다")
    void unreadable() {
        assertThatThrownBy(() -> read("code,code\nP00000001,P00000002\n", StandardCharsets.UTF_8, List.of("code")))
            .isInstanceOfSatisfying(MigrationException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(MigrationErrorCode.SOURCE_UNREADABLE);
                assertThat(exception.getMessage()).doesNotContain("P0000000");
            });
        assertThatThrownBy(() -> read("code,name\nP00000001,\"member0001@example.com\n", StandardCharsets.UTF_8, List.of("code")))
            .isInstanceOfSatisfying(MigrationException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(MigrationErrorCode.SOURCE_UNREADABLE);
                assertThat(exception.getMessage()).doesNotContain("example.com");
            });
    }
}
