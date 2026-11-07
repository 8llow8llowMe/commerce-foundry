package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate.Cell;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate.Row;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fail-closed chart boundary; no parser diagnostics or input values may escape. */
@Component
@RequiredArgsConstructor
public class SizeChartGenerationValidationProcessor {
    private static final int MAX_BYTES = 32 * 1024;
    private static final BigDecimal MAX_VALUE = new BigDecimal("9999.99");
    private static final Pattern SIZE_LABEL = Pattern.compile("(?i)(?:[2-9]?[X]{0,4}[SML]|XXS|XXXL|XXXXL|FREE|ONE(?: SIZE)?|OS|F|[0-9]{1,3}(?:XL)?|[0-9]{1,3}-[0-9]{1,3})");
    private static final Pattern COLUMN_NAME = Pattern.compile("[\\p{L}][\\p{L} /()_-]{0,29}");
    private static final Pattern SOURCE_TOKEN = Pattern.compile(
        "(?i)(?:[2-9]?[X]{0,4}[SML]|XXS|XXXL|XXXXL|FREE|ONE|SIZE|OS|F|CM|INCH|IN|N/A|NULL|[0-9]{1,4}(?:[.][0-9]{1,2})?)");
    private static final List<String> SIZE_ORDER = List.of("XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "XXXXL");
    private final ObjectMapper mapper;

    public record Payload(long draftId, String inputHash) {
    }

    public record Template(String unit, List<Long> columnIds) {
        public Template {
            columnIds = List.copyOf(columnIds);
        }
    }

    public record Warning(String code, int rowIndex, long columnId) {
    }

    public record SanitizedCandidate(String unit, List<Row> rows, List<Warning> warnings) {
        @Override
        public String toString() {
            return "SanitizedCandidate[redacted]";
        }
    }

    public Payload payload(String json) {
        JsonNode node = parse(json, 512);
        fields(node, Set.of("draftId", "inputHash"));
        long draftId = positiveId(node.get("draftId"));
        String hash = string(node.get("inputHash"));
        require(hash.matches("[a-f0-9]{64}"));
        return new Payload(draftId, hash);
    }

    public Template input(SizeChartGenerationInputQueryResult input) {
        JsonNode source = parse(input.sourceJson(), MAX_BYTES);
        // IMAGE is intentionally unsupported until an approved byte/hash-bound PII-safe boundary exists.
        require("TEXT".equals(string(source.get("mode"))));
        fields(source, Set.of("mode", "text"));
        String text = string(source.get("text"));
        require(!text.isBlank() && text.length() <= 12000);
        JsonNode template = parse(input.templateJson(), MAX_BYTES);
        fields(template, Set.of("unit", "columns"));
        String unit = string(template.get("unit"));
        require(Set.of("CM", "INCH").contains(unit));
        JsonNode columns = template.get("columns");
        require(columns != null && columns.isArray() && columns.size() >= 1 && columns.size() <= 20);
        List<Long> ids = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode column : columns) {
            fields(column, Set.of("id", "name"));
            long id = positiveId(column.get("id"));
            String name = string(column.get("name"));
            require(!ids.contains(id) && COLUMN_NAME.matcher(name).matches() && names.add(name));
            ids.add(id);
        }
        // Accept chart vocabulary only. Generic PII masking cannot establish that free prose contains no names.
        // Longest name first avoids partial replacement when template labels overlap.
        for (String name : names.stream().sorted((a, b) -> Integer.compare(b.length(), a.length())).toList()) {
            text = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).replaceAll(" ");
        }
        for (String token : text.split("[\\s,;:|/()=+\\-]+")) {
            require(token.isEmpty() || SOURCE_TOKEN.matcher(token).matches());
            String normalized = token.toUpperCase(Locale.ROOT);
            if (Set.of("CM", "INCH", "IN").contains(normalized)) {
                require(unit.equals(normalized.equals("IN") ? "INCH" : normalized));
            }
        }
        return new Template(unit, ids);
    }

    public String outcome(SizeChartGenerationCandidate candidate, Template template) {
        require(candidate != null && template.unit().equals(candidate.unit()));
        require(candidate.rows() != null && !candidate.rows().isEmpty() && candidate.rows().size() <= 30);
        List<Row> rows = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        Map<Long, BigDecimal> previous = new LinkedHashMap<>();
        int previousRank = -1;
        for (int rowIndex = 0; rowIndex < candidate.rows().size(); rowIndex++) {
            Row row = candidate.rows().get(rowIndex);
            require(row != null && row.sizeLabel() != null && row.sizeLabel().length() <= 20);
            String label = row.sizeLabel().toUpperCase(Locale.ROOT);
            require(SIZE_LABEL.matcher(label).matches() && labels.add(label));
            int rank = SIZE_ORDER.indexOf(label);
            if (rank < 0 && label.matches("[0-9]{1,3}")) {
                rank = Integer.parseInt(label) + 100;
            }
            if (rank >= 0 && previousRank >= 0 && rank < previousRank) {
                warnings.add(new Warning("SIZE_ORDER", rowIndex, 0));
            }
            previousRank = rank;
            require(row.cells() != null && row.cells().size() <= template.columnIds().size());
            Map<Long, BigDecimal> values = new LinkedHashMap<>();
            for (Cell cell : row.cells()) {
                require(cell != null && template.columnIds().contains(cell.columnId()) && !values.containsKey(cell.columnId()));
                BigDecimal value = cell.value();
                require(value == null || (value.signum() >= 0 && value.compareTo(MAX_VALUE) <= 0 && value.scale() <= 2 && value.scale() >= 0));
                values.put(cell.columnId(), value);
            }
            List<Cell> cells = new ArrayList<>();
            for (long columnId : template.columnIds()) {
                BigDecimal value = values.get(columnId);
                cells.add(new Cell(columnId, value));
                if (value == null) {
                    warnings.add(new Warning("MISSING_CELL", rowIndex, columnId));
                } else {
                    BigDecimal before = previous.get(columnId);
                    if (value.signum() == 0 || value.compareTo(new BigDecimal("300")) > 0
                        || (before != null && before.signum() > 0
                            && (value.compareTo(before.multiply(new BigDecimal("2"))) > 0
                                || value.multiply(new BigDecimal("2")).compareTo(before) < 0))) {
                        warnings.add(new Warning("MEASUREMENT_OUTLIER", rowIndex, columnId));
                    }
                    if (before != null && value.compareTo(before) < 0) {
                        warnings.add(new Warning("MEASUREMENT_ORDER", rowIndex, columnId));
                    }
                }
                previous.put(columnId, value);
            }
            rows.add(new Row(label, List.copyOf(cells)));
        }
        try {
            String json = mapper.writeValueAsString(new SanitizedCandidate(template.unit(), List.copyOf(rows), List.copyOf(warnings)));
            require(json.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES);
            return json;
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    private JsonNode parse(String json, int maxBytes) {
        require(json != null && json.length() <= maxBytes && json.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
        try {
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            require(node != null && node.isObject());
            return node;
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    private void fields(JsonNode node, Set<String> fields) {
        require(node.isObject() && node.size() == fields.size() && fields.stream().allMatch(node::has));
    }

    private String string(JsonNode node) {
        require(node != null && node.isString());
        return node.stringValue();
    }

    private long positiveId(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0);
        return node.longValue();
    }

    private void require(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private JobExecutionException invalid() {
        return new JobExecutionException("SIZE_CHART_INVALID_DATA", false);
    }
}
