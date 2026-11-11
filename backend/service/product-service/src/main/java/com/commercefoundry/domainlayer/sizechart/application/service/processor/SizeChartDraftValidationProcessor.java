package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.info.SizeChartDraftApplyInfo;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftImage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartImageLimits;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartDraft;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartLimits;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fail closed: masking alone cannot prove that prose contains no names or unformatted addresses. */
@Component
public class SizeChartDraftValidationProcessor {
    private static final int MAX_TEXT = 12000;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxNumberLength(16).maxStringLength(12000).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final PiiMasker MASKER = new PiiMasker(Map.of());
    private static final Pattern TOKEN = Pattern.compile(
        "(?i)(?:[2-9]?[X]{0,4}[SML]|XXS|XXXL|XXXXL|FREE|ONE|SIZE|OS|F|CM|INCH|IN|N/A|NULL|[0-9]{1,4}(?:[.][0-9]{1,2})?)");
    private static final Pattern LABEL = Pattern.compile(
        "(?i)(?:[2-9]?[X]{0,4}[SML]|XXS|XXXL|XXXXL|FREE|ONE(?: SIZE)?|OS|F|[0-9]{1,3}(?:XL)?|[0-9]{1,3}-[0-9]{1,3})");
    // AI-only vocabulary. Manual #216 templates remain unrestricted by this policy.
    private static final Set<String> MEASUREMENTS = Set.of(
        "가슴", "가슴둘레", "가슴단면", "총장", "기장", "어깨", "어깨너비", "어깨넓이", "소매", "소매길이",
        "소매통", "소매단", "암홀", "허리", "허리둘레", "허리단면", "엉덩이", "엉덩이둘레", "힙", "힙둘레",
        "허벅지", "허벅지둘레", "밑위", "앞밑위", "뒤밑위", "밑단", "밑단둘레", "인심", "밑가슴둘레",
        "목둘레", "팔길이", "팔둘레", "손목둘레", "발길이", "발볼", "굽높이", "길이", "너비", "폭", "높이",
        "chest", "chest circumference", "chest width", "bust", "underbust", "length", "total length", "body length",
        "shoulder", "shoulder width", "sleeve", "sleeve length", "sleeve width", "cuff", "armhole",
        "waist", "waist circumference", "waist width", "hip", "hips", "hip circumference", "thigh", "thigh circumference",
        "rise", "front rise", "back rise", "hem", "hem width", "inseam", "outseam", "neck circumference",
        "arm length", "arm circumference", "wrist circumference", "foot length", "foot width", "heel height", "width", "height");
    private static final Set<String> WARNINGS = Set.of("SIZE_ORDER", "MISSING_CELL", "MEASUREMENT_OUTLIER", "MEASUREMENT_ORDER");
    private static final String TEXT_MODE = "TEXT";
    private static final String IMAGE_MODE = "IMAGE";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IMAGE_SOURCE_ID = Pattern.compile(SizeChartImageLimits.SOURCE_ID_PATTERN);
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    public String normalizedRequestHash(SizeChartDraftCreateCommand command) {
        require(command != null && command.productId() > 0 && command.templateId() > 0 && command.actorId() > 0
            && command.idempotencyKey() != null && (TEXT_MODE.equals(command.mode()) || IMAGE_MODE.equals(command.mode())),
            SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        if (TEXT_MODE.equals(command.mode())) {
            require(command.images().isEmpty(), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
            String text = normalize(command.sourceText());
            // 기존 TEXT 초안의 멱등 해시와 같아야 7일 안의 재시도가 같은 초안을 돌려준다. 모양을 바꾸지 않는다.
            return hash(json(List.of(command.productId(), command.templateId(), TEXT_MODE, text)));
        }
        var images = command.images();
        require(command.sourceText() == null && command.personalInfoRemovedConfirmed() && !images.isEmpty() && images.size() <= SizeChartImageLimits.MAX_IMAGES
            && images.stream().allMatch(i -> i != null && i.sourceId() != null && IMAGE_SOURCE_ID.matcher(i.sourceId()).matches()
                && i.approvedContentHash() != null && SHA256.matcher(i.approvedContentHash()).matches())
            && images.stream().map(SizeChartDraftCreateCommand.Image::sourceId).distinct().count() == images.size(),
            SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        // 순서도 입력이다. 승인 해시가 다르면 같은 키라도 다른 요청이다(409).
        return hash(json(List.of(command.productId(), command.templateId(), IMAGE_MODE,
            images.stream().map(i -> List.of(i.sourceId(), i.approvedContentHash())).toList(), true)));
    }

    /** IMAGE 입력 스냅숏. 키는 product 안에서만 쓰고 internal 응답에는 싣지 않는다. */
    public String imageSource(SizeChartTemplate template, List<SizeChartTemplateColumn> columns, List<SizeChartDraftImage> images) {
        template(template, columns);
        require(images != null && !images.isEmpty() && images.size() <= SizeChartImageLimits.MAX_IMAGES
            && images.stream().allMatch(this::validImage)
            && images.stream().map(SizeChartDraftImage::key).distinct().count() == images.size(), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        return json(ordered("mode", IMAGE_MODE, "images", images.stream()
            .map(i -> ordered("key", i.key(), "sha256", i.sha256(), "contentType", i.contentType(), "length", i.length())).toList()));
    }

    /** 저장된 입력의 방식. 알 수 없는 모양이면 계약 오류다. */
    public String mode(String sourceJson) {
        String mode = string(object(sourceJson).get("mode"));
        require(TEXT_MODE.equals(mode) || IMAGE_MODE.equals(mode), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        return mode;
    }

    /** 저장된 IMAGE 입력의 승인 이미지. TEXT 면 비어 있다. */
    public List<SizeChartDraftImage> images(String sourceJson) {
        JsonNode node = object(sourceJson);
        if (TEXT_MODE.equals(mode(sourceJson))) {
            return List.of();
        }
        fields(node, Set.of("mode", "images"));
        JsonNode images = node.get("images");
        require(images.isArray() && !images.isEmpty() && images.size() <= SizeChartImageLimits.MAX_IMAGES, SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        List<SizeChartDraftImage> result = new ArrayList<>();
        for (JsonNode image : images) {
            fields(image, Set.of("key", "sha256", "contentType", "length"));
            JsonNode length = image.get("length");
            require(length.isIntegralNumber() && length.canConvertToLong(), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            var parsed = new SizeChartDraftImage(string(image.get("key")), string(image.get("sha256")), string(image.get("contentType")), length.longValue());
            require(validImage(parsed), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            result.add(parsed);
        }
        return List.copyOf(result);
    }

    /** internal 생성 입력으로 내보내는 IMAGE 입력. 키를 빼고 순서 · 해시 · 형식만 남긴다. */
    public String publicImageSource(List<SizeChartDraftImage> images) {
        return json(ordered("mode", IMAGE_MODE, "images", images.stream()
            .map(i -> ordered("sha256", i.sha256(), "contentType", i.contentType())).toList()));
    }

    private boolean validImage(SizeChartDraftImage image) {
        return image != null && image.key() != null && image.key().length() <= 512 && image.key().matches("products/[0-9a-z/._-]+")
            && !image.key().contains("..") && image.sha256() != null && SHA256.matcher(image.sha256()).matches()
            && IMAGE_TYPES.contains(image.contentType()) && image.length() > 0 && image.length() <= SizeChartImageLimits.MAX_IMAGE_BYTES;
    }

    public String source(SizeChartDraftCreateCommand command, SizeChartTemplate template, List<SizeChartTemplateColumn> columns) {
        // Validate independently: a caller cannot use a custom column name to bless unknown source text.
        template(template, columns);
        String text = MASKER.mask(normalize(command.sourceText()), List.of());
        // Masked spans are discarded rather than forwarded as unknown chart vocabulary.
        text = text.replaceAll("\\[(?:PII|EMAIL|RRN|CARD|PHONE|ACCOUNT|ADDRESS)\\]", " ")
            .replaceAll("(?i)([0-9])(?=cm\\b|inch\\b|in\\b)", "$1 ").replaceAll("\\s+", " ").strip();
        String vocabulary = text;
        for (String name : columns.stream().map(SizeChartTemplateColumn::name)
            .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            // Whole chart labels only; do not erase a label embedded inside an unknown word.
            vocabulary = vocabulary.replaceAll("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])", " ");
        }
        boolean measurement = false;
        for (String token : vocabulary.split("[\\s,;:|/()=+\\-]+")) {
            require(token.isEmpty() || TOKEN.matcher(token).matches(), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
            measurement |= token.matches("[0-9]{1,4}(?:[.][0-9]{1,2})?");
            String unit = token.toUpperCase(Locale.ROOT);
            if (Set.of("CM", "INCH", "IN").contains(unit)) {
                require(template.baseUnit().name().equals(unit.equals("IN") ? "INCH" : unit), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
            }
        }
        require(measurement, SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        return json(ordered("mode", "TEXT", "text", text));
    }

    public String template(SizeChartTemplate template, List<SizeChartTemplateColumn> columns) {
        require(template != null && template.baseUnit() != null && !columns.isEmpty() && columns.size() <= 20,
            SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        Set<Long> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (var column : columns) {
            require(column.id() > 0 && column.templateId() == template.id() && ids.add(column.id()) && column.name() != null
                && MEASUREMENTS.contains(column.name().toLowerCase(Locale.ROOT)) && names.add(column.name().toLowerCase(Locale.ROOT)),
                SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
            require(column.name().equals(MASKER.mask(column.name(), List.of())), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        }
        return json(ordered("unit", template.baseUnit().name(), "columns", columns.stream()
            .map(c -> ordered("id", c.id(), "name", c.name())).toList()));
    }

    public SizeChartDraft.Candidate candidate(String json, String templateJson) {
        JsonNode node = object(json);
        JsonNode template = object(templateJson);
        fields(node, Set.of("unit", "rows", "warnings"));
        require(string(node.get("unit")).equals(string(template.get("unit"))), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        Set<Long> ids = new HashSet<>();
        for (JsonNode column : template.get("columns")) {
            ids.add(id(column.get("id")));
        }
        JsonNode rows = node.get("rows");
        require(rows.isArray() && rows.size() >= 1 && rows.size() <= 30, SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        Set<String> labels = new HashSet<>();
        List<Map<String, Object>> safeRows = new ArrayList<>();
        for (JsonNode row : rows) {
            fields(row, Set.of("sizeLabel", "cells"));
            String label = string(row.get("sizeLabel")).toUpperCase(Locale.ROOT);
            require(label.length() <= 20 && LABEL.matcher(label).matches() && labels.add(label), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            JsonNode cells = row.get("cells");
            require(cells.isArray() && cells.size() == ids.size(), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            Set<Long> seen = new HashSet<>();
            List<Map<String, Object>> safeCells = new ArrayList<>();
            for (JsonNode cell : cells) {
                fields(cell, Set.of("columnId", "value"));
                long columnId = id(cell.get("columnId"));
                require(ids.contains(columnId) && seen.add(columnId), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
                JsonNode value = cell.get("value");
                BigDecimal decimal = null;
                if (!value.isNull()) {
                    require(value.isNumber(), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
                    decimal = value.decimalValue();
                    require(decimal.signum() >= 0 && decimal.compareTo(new BigDecimal("9999.99")) <= 0
                        && decimal.scale() >= 0 && decimal.scale() <= 2, SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
                }
                safeCells.add(ordered("columnId", columnId, "value", decimal));
            }
            safeRows.add(ordered("sizeLabel", label, "cells", safeCells));
        }
        JsonNode warnings = node.get("warnings");
        require(warnings.isArray() && warnings.size() <= 1800, SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        List<Map<String, Object>> safeWarnings = new ArrayList<>();
        for (JsonNode warning : warnings) {
            fields(warning, Set.of("code", "rowIndex", "columnId"));
            String code = string(warning.get("code"));
            JsonNode row = warning.get("rowIndex");
            JsonNode column = warning.get("columnId");
            require(WARNINGS.contains(code) && row.isIntegralNumber() && row.canConvertToInt() && row.intValue() >= 0 && row.intValue() < rows.size()
                && column.isIntegralNumber() && column.canConvertToLong(), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            long columnId = column.longValue();
            require(code.equals("SIZE_ORDER") ? columnId == 0 : ids.contains(columnId), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            safeWarnings.add(ordered("code", code, "rowIndex", row.intValue(), "columnId", columnId));
        }
        return SizeChartDraft.Candidate.of(json(ordered("unit", string(node.get("unit")), "rows", safeRows, "warnings", safeWarnings)));
    }

    /** Same bounds and text rules as #216, also enforced for application callers without web DTO validation. */
    public ProductSizeChartUpsertCommand corrected(ProductSizeChartUpsertCommand command) {
        require(command != null && command.templateId() > 0 && command.rows() != null
            && command.rows().size() >= SizeChartLimits.MIN_ROWS && command.rows().size() <= SizeChartLimits.MAX_ROWS,
            SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        String note = text(command.note(), false, SizeChartLimits.MAX_NOTE_CODE_POINTS);
        List<ProductSizeChartUpsertCommand.Row> rows = new ArrayList<>();
        for (var row : command.rows()) {
            require(row != null && row.displayOrder() >= 0 && row.cells() != null && row.cells().size() <= SizeChartLimits.MAX_COLUMNS,
                SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
            String label = text(row.sizeLabel(), true, SizeChartLimits.MAX_SIZE_LABEL_CODE_POINTS);
            List<ProductSizeChartUpsertCommand.Cell> cells = new ArrayList<>();
            for (var cell : row.cells()) {
                require(cell != null && cell.columnId() > 0 && cell.value() != null && cell.value().signum() > 0
                    && cell.value().compareTo(SizeChartLimits.MAX_MEASUREMENT) <= 0 && cell.value().scale() <= SizeChartLimits.MEASUREMENT_SCALE,
                    SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
                cells.add(new ProductSizeChartUpsertCommand.Cell(cell.columnId(), cell.value().setScale(SizeChartLimits.MEASUREMENT_SCALE)));
            }
            rows.add(new ProductSizeChartUpsertCommand.Row(label, row.displayOrder(), cells.stream()
                .sorted(Comparator.comparingLong(ProductSizeChartUpsertCommand.Cell::columnId)).toList()));
        }
        return new ProductSizeChartUpsertCommand(command.templateId(), note, rows.stream()
            .sorted(Comparator.comparingInt(ProductSizeChartUpsertCommand.Row::displayOrder).thenComparing(ProductSizeChartUpsertCommand.Row::sizeLabel)).toList());
    }

    private String text(String value, boolean required, int max) {
        require(!required || value != null && !value.isBlank(), SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        if (value == null) {
            return null;
        }
        require(value.codePoints().noneMatch(c -> Character.getType(c) == Character.CONTROL || Character.getType(c) == Character.FORMAT),
            SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        String normalized = value.strip();
        require(normalized.codePointCount(0, normalized.length()) <= max, SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        return normalized.isEmpty() ? null : normalized;
    }

    public String applyHash(ProductSizeChartUpsertCommand canonical, Set<String> acknowledgments) {
        require(acknowledgments != null && acknowledgments.size() <= 1800 && acknowledgments.stream().allMatch(java.util.Objects::nonNull),
            SizeChartDraftTransportErrorCode.WARNING_ACK_REQUIRED);
        return hash(json(List.of(canonical, acknowledgments.stream().sorted().toList())));
    }

    public Set<Long> capturedColumnIds(String templateJson) {
        Set<Long> ids = new HashSet<>();
        for (JsonNode column : object(templateJson).get("columns")) {
            ids.add(id(column.get("id")));
        }
        return Set.copyOf(ids);
    }

    /** Stable public identifier; the server never accepts warning descriptions from the reviewer. */
    public static String warningId(String code, int rowIndex, long columnId) {
        return code + ":" + rowIndex + ":" + columnId;
    }

    /**
     * Acknowledgments cover generated warnings already stored and exposed to the reviewer. Re-evaluate those warnings
     * against the corrected rows; do not invent undiscoverable IDs for new operator edits. New operator-entered anomalies
     * follow the existing #216 manual PUT bounds/completeness rules. A broader warning policy needs a review-preview API
     * that returns the new server-issued IDs before apply. Client-provided warning strings are never trusted.
     */
    public void requireAcknowledgments(SizeChartDraft.Candidate stored, ProductSizeChartUpsertCommand corrected, Set<String> acknowledged) {
        JsonNode candidate = object(stored.json());
        Set<String> known = new HashSet<>();
        Set<String> required = new HashSet<>();
        for (JsonNode warning : candidate.get("warnings")) {
            String code = string(warning.get("code"));
            int index = warning.get("rowIndex").intValue();
            long column = warning.get("columnId").longValue();
            String id = warningId(code, index, column);
            known.add(id);
            if (!code.equals("MISSING_CELL") && unresolved(code, index, column, candidate, corrected)) {
                required.add(id);
            }
        }
        require(known.containsAll(acknowledged) && acknowledged.containsAll(required), SizeChartDraftTransportErrorCode.WARNING_ACK_REQUIRED);
    }

    private boolean unresolved(String code, int originalIndex, long column, JsonNode candidate, ProductSizeChartUpsertCommand chart) {
        String label = string(candidate.get("rows").get(originalIndex).get("sizeLabel"));
        for (int i = 0; i < chart.rows().size(); i++) {
            var row = chart.rows().get(i);
            if (!row.sizeLabel().equalsIgnoreCase(label)) {
                continue;
            }
            var previous = i == 0 ? null : chart.rows().get(i - 1);
            if (code.equals("SIZE_ORDER")) {
                int rank = sizeRank(row.sizeLabel());
                int before = previous == null ? -1 : sizeRank(previous.sizeLabel());
                return rank >= 0 && before >= 0 && rank < before;
            }
            BigDecimal value = cell(row, column);
            BigDecimal before = previous == null ? null : cell(previous, column);
            if (value == null) {
                return true; // Completeness is checked by #216; acknowledgment cannot fill a cell.
            }
            if (code.equals("MEASUREMENT_ORDER")) {
                return before != null && value.compareTo(before) < 0;
            }
            return value.compareTo(new BigDecimal("300")) > 0 || before != null
                && (value.compareTo(before.multiply(new BigDecimal("2"))) > 0 || value.multiply(new BigDecimal("2")).compareTo(before) < 0);
        }
        return false;
    }

    private int sizeRank(String label) {
        String value = label.toUpperCase(Locale.ROOT);
        int rank = List.of("XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "XXXXL").indexOf(value);
        return rank < 0 && value.matches("[0-9]{1,3}") ? Integer.parseInt(value) + 100 : rank;
    }

    private BigDecimal cell(ProductSizeChartUpsertCommand.Row row, long columnId) {
        return row.cells().stream().filter(c -> c.columnId() == columnId).map(ProductSizeChartUpsertCommand.Cell::value).findFirst().orElse(null);
    }

    public SizeChartDraft.Candidate reviewed(ProductSizeChartUpsertCommand canonical, String templateJson) {
        String unit = string(object(templateJson).get("unit"));
        var rows = canonical.rows().stream().map(row -> ordered("sizeLabel", row.sizeLabel(), "cells",
            row.cells().stream().map(cell -> ordered("columnId", cell.columnId(), "value", cell.value())).toList())).toList();
        return candidate(json(ordered("unit", unit, "rows", rows, "warnings", List.of())), templateJson);
    }

    public String receipt(SizeChartDraftApplyInfo info) {
        return json(ordered("draftId", info.draftId(), "productId", info.productId(), "chartId", info.chartId(), "chartRevision", info.chartRevision(),
            "appliedByMemberId", info.appliedByMemberId(), "appliedAt", info.appliedAt().toString()));
    }

    public SizeChartDraftApplyInfo receipt(SizeChartDraft.ApplyReceipt receipt) {
        JsonNode node = object(receipt.receiptJson());
        return new SizeChartDraftApplyInfo(id(node.get("draftId")), id(node.get("productId")), id(node.get("chartId")),
            node.get("chartRevision").longValue(), receipt.appliedByMemberId(), receipt.appliedAt());
    }

    public String inputHash(SizeChartDraft.Input input) {
        return hash(json(List.of(input.productId(), input.templateId(), input.templateRevision(), input.sourceJson(), input.templateJson(),
            input.baseChartId() == null ? "" : input.baseChartId(), input.baseChartRevision() == null ? "" : input.baseChartRevision())));
    }

    private String normalize(String value) {
        require(value != null && !value.isBlank() && value.length() <= MAX_TEXT, SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        require(normalized.length() <= MAX_TEXT && normalized.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'),
            SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        return normalized.replaceAll("\\s+", " ").strip();
    }

    private JsonNode object(String json) {
        require(json != null && json.length() <= SizeChartDraft.MAX_JSON_LENGTH
            && json.getBytes(StandardCharsets.UTF_8).length <= SizeChartDraft.MAX_JSON_LENGTH, SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        try {
            JsonNode node = JSON.readTree(json);
            require(node != null && node.isObject(), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
            return node;
        } catch (JacksonException | ArithmeticException exception) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        }
    }

    private void fields(JsonNode node, Set<String> fields) {
        require(node != null && node.isObject() && node.size() == fields.size() && fields.stream().allMatch(node::has),
            SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
    }

    private String string(JsonNode node) {
        require(node != null && node.isString(), SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        return node.stringValue();
    }

    private long id(JsonNode node) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0, SizeChartDraftTransportErrorCode.INVALID_CONTRACT);
        return node.longValue();
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private String json(Object value) {
        try {
            String json = JSON.writeValueAsString(value);
            require(json.length() <= SizeChartDraft.MAX_JSON_LENGTH && json.getBytes(StandardCharsets.UTF_8).length <= SizeChartDraft.MAX_JSON_LENGTH,
                SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
            return json;
        } catch (JacksonException exception) {
            throw new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.UNSAFE_INPUT);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private void require(boolean valid, SizeChartDraftTransportErrorCode code) {
        if (!valid) {
            throw new SizeChartDraftTransportException(code);
        }
    }
}
