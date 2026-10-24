package com.commercefoundry.domainlayer.migration.adapter.out.file;

import com.commercefoundry.domainlayer.migration.application.exception.MigrationErrorCode;
import com.commercefoundry.domainlayer.migration.application.exception.MigrationException;
import com.commercefoundry.domainlayer.migration.application.port.out.MigrationSourceReaderPort;
import com.commercefoundry.domainlayer.migration.domain.model.SourceRow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.stereotype.Component;

/**
 * CSV 내보내기 읽기 — Apache Commons CSV(RFC 4180: 따옴표 · 칸 안 쉼표 · 칸 안 줄바꿈). 셀은 자르거나 다듬지 않는다(정규화는 domain).
 * 칸이 모자란 행은 모자란 칸을 비운 것으로 본다. 파서 예외 메시지는 셀 값을 담을 수 있어 원인으로만 넘긴다.
 */
@Component
public class CsvMigrationSourceReader implements MigrationSourceReaderPort {

    /** UTF-8 로 저장한 엑셀 CSV 의 머리 BOM(U+FEFF). 첫 컬럼명에 붙지 않게 버린다. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreEmptyLines(true)
        .setAllowMissingColumnNames(false)
        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
        .get();

    @Override
    public List<SourceRow> read(InputStream input, Charset charset, Collection<String> requiredColumns) {
        try (Reader reader = withoutByteOrderMark(new BufferedReader(new InputStreamReader(input, charset)));
             CSVParser parser = FORMAT.parse(reader)) {
            List<String> headers = parser.getHeaderNames();
            if (!headers.containsAll(requiredColumns)) {
                throw new MigrationException(MigrationErrorCode.SOURCE_COLUMN_MISSING);
            }
            List<SourceRow> rows = new ArrayList<>();
            int rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                Map<String, String> cells = new HashMap<>();
                for (int index = 0; index < headers.size() && index < record.size(); index++) {
                    cells.put(headers.get(index), record.get(index));
                }
                rows.add(new SourceRow(rowNumber, cells));
            }
            return rows;
        } catch (MigrationException exception) {
            throw exception;
        } catch (IOException | UncheckedIOException | IllegalArgumentException | IllegalStateException exception) {
            throw new MigrationException(MigrationErrorCode.SOURCE_UNREADABLE, exception);
        }
    }

    private static Reader withoutByteOrderMark(BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != BYTE_ORDER_MARK) {
            reader.reset();
        }
        return reader;
    }
}
