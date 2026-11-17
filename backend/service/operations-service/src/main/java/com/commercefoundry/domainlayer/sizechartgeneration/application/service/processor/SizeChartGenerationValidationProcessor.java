package com.commercefoundry.domainlayer.sizechartgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationAiPort.Attachment;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationImageQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate.Cell;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate.Row;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartImageGenerationCandidate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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
    /** product {@code SizeChartImageLimits.MAX_IMAGES} 와 같다. ai-core {@code AiImage.MAX_COUNT}(8) 안이다. */
    public static final int MAX_IMAGE_COUNT = 4;
    /** product {@code SizeChartImageLimits.MAX_IMAGE_BYTES} · ai-core {@code AiImage.MAX_BYTES} 와 같다. */
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final String JPEG = "image/jpeg";
    private static final String PNG = "image/png";
    private static final String WEBP = "image/webp";
    private static final Set<String> IMAGE_TYPES = Set.of(JPEG, PNG, WEBP);
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    /** magic bytes 판별에 필요한 앞부분 길이(WebP 12바이트). */
    private static final int MAGIC_BYTES = 12;
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

    /**
     * Validated IMAGE input.
     *
     * @param images     pinned metadata in read order
     * @param imagesJson prompt data slot: index and MIME only (no hash, no key)
     */
    public record ImageInput(Template template, List<SizeChartGenerationInputQueryResult.Image> images, String imagesJson) {
        public ImageInput {
            images = List.copyOf(images);
        }

        public List<Integer> indexes() {
            return images.stream().map(SizeChartGenerationInputQueryResult.Image::index).toList();
        }

        @Override
        public String toString() {
            return "ImageInput[images=" + images.size() + "]";
        }
    }

    private record Position(int rowIndex, long columnId) {
    }

    /** {@code TEXT} 면 false, {@code IMAGE} 면 true. 그 밖의 값은 계약 오류다. */
    public boolean isImage(SizeChartGenerationInputQueryResult input) {
        require(input != null && ("TEXT".equals(input.mode()) || "IMAGE".equals(input.mode())));
        return "IMAGE".equals(input.mode());
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
        // TEXT only here. IMAGE goes through imageInput(): pinned byte hashes approved by the operator (PII removal confirmed).
        require("TEXT".equals(input.mode()) && input.images().isEmpty());
        JsonNode source = parse(input.sourceJson(), MAX_BYTES);
        require("TEXT".equals(string(source.get("mode"))));
        fields(source, Set.of("mode", "text"));
        String text = string(source.get("text"));
        require(!text.isBlank() && text.length() <= 12000);
        List<Long> ids = new ArrayList<>();
        Set<String> names = new HashSet<>();
        String unit = template(input.templateJson(), ids, names);
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

    /**
     * IMAGE 입력. {@code sourceJson} 은 product 가 키를 뺀 {@code {"mode":"IMAGE","images":[{"sha256","contentType"}]}} 이고, 응답 메타데이터와 순서 ·
     * 해시 · 형식이 모두 같아야 한다. 1 ~ {@link #MAX_IMAGE_COUNT} 장, JPEG · PNG · WebP 만이다. 어긋나면 AI 를 부르지 않는다.
     */
    public ImageInput imageInput(SizeChartGenerationInputQueryResult input) {
        require("IMAGE".equals(input.mode()));
        JsonNode source = parse(input.sourceJson(), MAX_BYTES);
        fields(source, Set.of("mode", "images"));
        require("IMAGE".equals(string(source.get("mode"))));
        JsonNode images = source.get("images");
        List<SizeChartGenerationInputQueryResult.Image> pinned = input.images();
        require(images.isArray() && images.size() >= 1 && images.size() <= MAX_IMAGE_COUNT && images.size() == pinned.size());
        Set<String> hashes = new HashSet<>();
        List<Map<String, Object>> promptImages = new ArrayList<>();
        for (int position = 0; position < pinned.size(); position++) {
            JsonNode image = images.get(position);
            fields(image, Set.of("sha256", "contentType"));
            String sha256 = string(image.get("sha256"));
            String contentType = string(image.get("contentType"));
            var expected = pinned.get(position);
            require(expected != null && expected.index() == position && SHA256.matcher(sha256).matches() && IMAGE_TYPES.contains(contentType)
                && sha256.equals(expected.sha256()) && contentType.equals(expected.contentType()) && hashes.add(sha256));
            Map<String, Object> promptImage = new LinkedHashMap<>();
            promptImage.put("index", position);
            promptImage.put("contentType", contentType);
            promptImages.add(promptImage);
        }
        List<Long> ids = new ArrayList<>();
        String unit = template(input.templateJson(), ids, new HashSet<>());
        try {
            return new ImageInput(new Template(unit, ids), pinned, mapper.writeValueAsString(Map.of("images", promptImages)));
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    /**
     * 방금 받은 한 장을 본다 — 다음 장을 읽기 전에 부른다. 순번 · 크기 · magic bytes · SHA-256 이 승인 값과 다르면 {@code SIZE_CHART_IMAGE_CHANGED}
     * (재시도 불가)다. product 도 읽는 시점에 해시를 다시 대조하지만 operations 가 받은 바이트로 한 번 더 확인한다.
     */
    public Attachment image(ImageInput input, List<Attachment> accepted, SizeChartGenerationImageQueryResult received) {
        int position = accepted.size();
        if (received == null || position >= input.images().size()) {
            throw imageChanged();
        }
        var expected = input.images().get(position);
        if (received.index() != expected.index() || received.length() < 1 || received.length() > MAX_IMAGE_BYTES
            || !magic(expected.contentType(), received.head(MAGIC_BYTES)) || !expected.sha256().equals(received.withBytes(SizeChartGenerationValidationProcessor::sha256))) {
            throw imageChanged();
        }
        return new Attachment(expected.contentType(), received);
    }

    /** 승인한 장을 모두 받았는가. 모자라면 {@code SIZE_CHART_IMAGE_CHANGED}(재시도 불가). */
    public List<Attachment> images(ImageInput input, List<Attachment> accepted) {
        if (accepted.size() != input.images().size()) {
            throw imageChanged();
        }
        return List.copyOf(accepted);
    }

    /**
     * IMAGE 출력. TEXT 와 같은 검증을 거치고, 모델이 {@code uncertain=true} 로 표시한 값이 있는 칸에는 {@code UNCERTAIN_CELL} 경고를 더한다
     * (운영자가 원본과 대조해 확인해야 적용할 수 있다). 값이 없는 칸은 {@code MISSING_CELL} 만 낸다.
     */
    public String imageOutcome(SizeChartImageGenerationCandidate candidate, Template template) {
        require(candidate != null);
        Set<Position> uncertain = new HashSet<>();
        List<Row> rows = null;
        if (candidate.rows() != null) {
            rows = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < candidate.rows().size(); rowIndex++) {
                var row = candidate.rows().get(rowIndex);
                require(row != null);
                List<Cell> cells = null;
                if (row.cells() != null) {
                    cells = new ArrayList<>();
                    for (var cell : row.cells()) {
                        require(cell != null);
                        cells.add(new Cell(cell.columnId(), cell.value()));
                        if (Boolean.TRUE.equals(cell.uncertain()) && cell.value() != null) {
                            uncertain.add(new Position(rowIndex, cell.columnId()));
                        }
                    }
                }
                rows.add(new Row(row.sizeLabel(), cells));
            }
        }
        return outcome(new SizeChartGenerationCandidate(candidate.unit(), rows), template, uncertain);
    }

    public String outcome(SizeChartGenerationCandidate candidate, Template template) {
        return outcome(candidate, template, Set.of());
    }

    private String outcome(SizeChartGenerationCandidate candidate, Template template, Set<Position> uncertain) {
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
                    if (uncertain.contains(new Position(rowIndex, columnId))) {
                        warnings.add(new Warning("UNCERTAIN_CELL", rowIndex, columnId));
                    }
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

    /** 템플릿 스냅숏을 검증해 단위를 돌려주고 열 ID · 이름을 채운다. */
    private String template(String json, List<Long> ids, Set<String> names) {
        JsonNode template = parse(json, MAX_BYTES);
        fields(template, Set.of("unit", "columns"));
        String unit = string(template.get("unit"));
        require(Set.of("CM", "INCH").contains(unit));
        JsonNode columns = template.get("columns");
        require(columns != null && columns.isArray() && columns.size() >= 1 && columns.size() <= 20);
        for (JsonNode column : columns) {
            fields(column, Set.of("id", "name"));
            long id = positiveId(column.get("id"));
            String name = string(column.get("name"));
            require(!ids.contains(id) && COLUMN_NAME.matcher(name).matches() && names.add(name));
            ids.add(id);
        }
        return unit;
    }

    private static boolean magic(String contentType, byte[] bytes) {
        return switch (contentType) {
            case JPEG -> bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
            case PNG -> bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
            case WEBP -> bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static JobExecutionException imageChanged() {
        return new JobExecutionException(SizeChartGenerationRemoteFailure.Code.SIZE_CHART_IMAGE_CHANGED.name(), false);
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
