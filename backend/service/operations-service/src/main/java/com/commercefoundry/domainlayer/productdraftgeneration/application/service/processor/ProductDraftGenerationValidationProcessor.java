package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import com.commercefoundry.ai.client.AiImage;
import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationImageQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult.Facts;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query.ProductDraftGenerationInputQueryResult.Image;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationCandidate;
import com.commercefoundry.domainlayer.productdraftgeneration.domain.model.ProductDraftGenerationCandidate.ImageAlt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 상품 초안 생성의 닫힌 검증 경계 (#359 PR-2b, 선례: {@code SizeChartGenerationValidationProcessor}). 실패는 고정 코드만 내고 입력 · 출력 값 · 파서 진단을 싣지 않는다.
 *
 * <ul>
 *   <li><b>입력</b> — product 가 고정한 생성 입력을 다시 본다(product 요청 검증과 같은 상한). 사실 · 이미지 메타데이터가 계약 밖이면 AI 를 부르지 않는다.</li>
 *   <li><b>요청 크기</b> — 이미지는 {@link AiImage#MAX_COUNT}(8)장 · 장당 {@link AiImage#MAX_BYTES}(5MiB)이고, 원본 합계는 {@link #MAX_TOTAL_IMAGE_BYTES}(20MiB)다.
 *       ai-core 는 장수 · 장당 크기만 보고 요청 전체 크기는 보지 않는다 — Messages API 요청 본문 상한(32MB, 넘으면 413)에서 base64 4/3 배(약 26.7MB)를
 *       빼고 남는 몫을 프롬프트 · JSON 여유분으로 둔다. 바이트를 받기 전에 메타데이터 {@code byteLength} 합계로 막는다(재시도 불가).</li>
 *   <li><b>이미지 바이트</b> — 받은 크기 · magic bytes 가 고정 값과 다르면 바뀐 이미지로 본다(product 410 {@code PRODUCT_DRAFT_044} 와 같은 코드).</li>
 *   <li><b>출력</b> — AI 출력은 불신 입력이다. 길이 · 줄바꿈 · 제어 문자 · 링크 · 이미지 순번을 다시 보고, 확인이 필요한 속성 · 경고는 서버가 운영자 입력으로 계산한다.
 *       결과에 운영자 입력 사실을 다시 싣지 않는다 — 사실의 정본은 product 초안이다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationValidationProcessor {

    /** 이미지 원본 합계 상한. 근거는 클래스 설명. */
    public static final long MAX_TOTAL_IMAGE_BYTES = 20L * 1024 * 1024;
    /** 결과 JSON 상한 — 엔진 상한(64KiB)보다 작게 둔다. */
    public static final int MAX_RESULT_BYTES = 32 * 1024;

    public static final String INVALID_DATA = "PRODUCT_DRAFT_INVALID_DATA";
    public static final String INPUT_MISMATCH = "PRODUCT_DRAFT_INPUT_MISMATCH";
    public static final String IMAGES_TOO_LARGE = "PRODUCT_DRAFT_IMAGES_TOO_LARGE";
    public static final String IMAGE_CHANGED = "PRODUCT_DRAFT_IMAGE_CHANGED";

    private static final Set<String> STATUSES = Set.of("REQUESTED", "GENERATING");
    private static final Set<String> IMAGE_TYPES = Set.of("THUMBNAIL", "GALLERY");
    /** product 가 고정하는 형식(JPEG · PNG · WebP). GIF 는 product 가 받지 않는다. */
    private static final Set<String> CONTENT_TYPES = Set.of(AiImage.JPEG, AiImage.PNG, AiImage.WEBP);
    private static final int MAX_LIST = 10;
    private static final int MAX_ITEM = 100;

    private static final int NAME_MAX = 100;
    private static final int DESCRIPTION_MAX = 4000;
    private static final int TAG_MAX = 30;
    private static final int TAGS_MAX = 20;
    private static final int SEO_TITLE_MAX = 70;
    private static final int SEO_DESCRIPTION_MAX = 160;
    private static final int ALT_MAX = 200;

    private final ObjectMapper mapper;

    /** 확인이 필요한 속성 — 운영자가 넣지 않은 사실. AI 가 이미지로 정하지 않는다. */
    public enum UnconfirmedFact {
        MATERIALS, CARE_NOTES, MEASUREMENTS, ORIGIN, CERTIFICATIONS
    }

    /** 검증을 마친 입력. 프롬프트 데이터 칸 · 출력 검증에 쓴다. */
    public record Prepared(long draftId, String factsJson, String imagesJson, Facts facts, List<Image> images) {

        @Override
        public String toString() {
            return "Prepared[redacted]";
        }
    }

    /** 프롬프트 데이터 칸 — 운영자 입력 사실. 이 값만 사실이다. 비운 칸은 null · 빈 목록 그대로 보낸다. */
    public record PromptFacts(String nameHint, List<String> materials, List<String> careNotes, String measurementsNote, String origin,
                               List<String> certifications, String tone, List<String> mustInclude, List<String> mustAvoid) {
    }

    /** 프롬프트 데이터 칸 — 이미지 순번과 유형. 바이트는 요청의 이미지로 따로 붙는다. */
    public record PromptImage(int index, String imageType) {
    }

    /** 서버가 계산한 경고. {@code index} 는 운영자 입력 목록의 순번이다 — 문구를 다시 싣지 않는다. */
    public record Warning(String code, int index) {
    }

    /** 결과 JSON 의 제안 칸. */
    public record Suggestion(String name, String description, List<String> searchTags, String seoTitle, String seoDescription, List<ImageAlt> imageAlts) {
    }

    /** 작업 결과 JSON. 사실 칸이 없다. */
    public record Result(Suggestion suggestion, List<UnconfirmedFact> unconfirmedFacts, List<Warning> warnings) {

        @Override
        public String toString() {
            return "Result[redacted]";
        }
    }

    /** 등록 payload {@code {"draftId": <양수>}} 만 받는다. */
    public long payload(String json) {
        JsonNode node = parse(json, 256);
        require(node.size() == 1 && node.has("draftId"));
        JsonNode draftId = node.get("draftId");
        require(draftId.isIntegralNumber() && draftId.canConvertToLong() && draftId.longValue() > 0);
        return draftId.longValue();
    }

    /** product 생성 입력을 다시 검증한다. 초안 아이디가 다르면 {@code INPUT_MISMATCH}, 이미지 합계가 넘으면 {@code IMAGES_TOO_LARGE}(둘 다 재시도 불가). */
    public Prepared input(long draftId, ProductDraftGenerationInputQueryResult input) {
        if (input == null || input.draftId() != draftId) {
            throw new JobExecutionException(INPUT_MISMATCH, false);
        }
        require(oneOf(STATUSES, input.status()));
        Facts facts = input.facts();
        require(facts != null);
        fact(facts.nameHint(), MAX_ITEM, true);
        list(facts.materials());
        list(facts.careNotes());
        String measurements = facts.measurementsNote();
        // 치수 메모는 개행 · 탭을 받는다(product 입력 검증과 같다). 비교만 하고 값은 바꾸지 않는다
        require(measurements == null || (!measurements.isBlank() && measurements.length() <= 1000
            && !unsafe(measurements.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' '), true)));
        fact(facts.origin(), MAX_ITEM, true);
        list(facts.certifications());
        fact(facts.tone(), 200, true);
        list(facts.mustInclude());
        list(facts.mustAvoid());

        List<Image> images = input.images();
        require(input.imageCount() == images.size() && images.size() <= AiImage.MAX_COUNT);
        long total = 0;
        for (int index = 0; index < images.size(); index++) {
            Image image = images.get(index);
            require(image != null && image.index() == index && oneOf(IMAGE_TYPES, image.imageType()) && oneOf(CONTENT_TYPES, image.contentType())
                && image.byteLength() >= 1 && image.byteLength() <= AiImage.MAX_BYTES);
            total += image.byteLength();
        }
        if (total > MAX_TOTAL_IMAGE_BYTES) {
            throw new JobExecutionException(IMAGES_TOO_LARGE, false);
        }
        try {
            String factsJson = mapper.writeValueAsString(new PromptFacts(facts.nameHint(), facts.materials(), facts.careNotes(), facts.measurementsNote(),
                facts.origin(), facts.certifications(), facts.tone(), facts.mustInclude(), facts.mustAvoid()));
            String imagesJson = mapper.writeValueAsString(images.stream().map(image -> new PromptImage(image.index(), image.imageType())).toList());
            return new Prepared(draftId, factsJson, imagesJson, facts, images);
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    /** 받은 바이트가 고정한 크기 · 형식과 같은지 본다. 다르면 바뀐 이미지다(재시도 불가). */
    public List<ProductDraftGenerationImageQueryResult> images(Prepared prepared, List<ProductDraftGenerationImageQueryResult> received) {
        List<Image> expected = prepared.images();
        if (received == null || received.size() != expected.size()) {
            throw new JobExecutionException(IMAGE_CHANGED, false);
        }
        for (int index = 0; index < expected.size(); index++) {
            Image image = expected.get(index);
            ProductDraftGenerationImageQueryResult bytes = received.get(index);
            if (bytes == null || bytes.index() != image.index() || !image.contentType().equals(bytes.mediaType()) || bytes.length() != image.byteLength()
                || !magic(image.contentType(), bytes.bytes())) {
                throw new JobExecutionException(IMAGE_CHANGED, false);
            }
        }
        return List.copyOf(received);
    }

    /** AI 출력을 검증 · 정규화하고 결과 JSON 을 만든다. 계약 밖이면 {@code INVALID_DATA}(재시도 불가 — 같은 입력의 두 번째 유료 호출을 막는다). */
    public String outcome(ProductDraftGenerationCandidate candidate, Prepared prepared) {
        require(candidate != null);
        String name = singleLine(candidate.name(), NAME_MAX, false);
        String description = text(candidate.description());
        String seoTitle = singleLine(candidate.seoTitle(), SEO_TITLE_MAX, false);
        String seoDescription = singleLine(candidate.seoDescription(), SEO_DESCRIPTION_MAX, false);

        Set<String> seen = new HashSet<>();
        List<String> tags = new ArrayList<>();
        for (String tag : candidate.searchTags() == null ? List.<String>of() : candidate.searchTags()) {
            require(tag != null);
            if (tag.isBlank()) {
                continue;
            }
            String normalized = singleLine(tag, TAG_MAX, false);
            if (seen.add(normalized.toLowerCase(Locale.ROOT))) {
                tags.add(normalized);
            }
        }
        require(tags.size() <= TAGS_MAX);

        List<ImageAlt> alts = new ArrayList<>(candidate.imageAlts() == null ? List.of() : candidate.imageAlts());
        require(alts.size() == prepared.images().size());
        Set<Integer> indexes = new HashSet<>();
        List<ImageAlt> normalizedAlts = new ArrayList<>();
        for (ImageAlt alt : alts) {
            require(alt != null && alt.index() >= 0 && alt.index() < prepared.images().size() && indexes.add(alt.index()));
            normalizedAlts.add(new ImageAlt(alt.index(), singleLine(alt.altText(), ALT_MAX, false)));
        }
        normalizedAlts.sort(Comparator.comparingInt(ImageAlt::index));

        Suggestion suggestion = new Suggestion(name, description, List.copyOf(tags), seoTitle, seoDescription, List.copyOf(normalizedAlts));
        Result result = new Result(suggestion, unconfirmed(prepared.facts()), warnings(prepared.facts(), suggestion));
        try {
            String json = mapper.writeValueAsString(result);
            require(json.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_BYTES);
            return json;
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    private static List<UnconfirmedFact> unconfirmed(Facts facts) {
        List<UnconfirmedFact> missing = new ArrayList<>();
        if (facts.materials().isEmpty()) {
            missing.add(UnconfirmedFact.MATERIALS);
        }
        if (facts.careNotes().isEmpty()) {
            missing.add(UnconfirmedFact.CARE_NOTES);
        }
        if (facts.measurementsNote() == null) {
            missing.add(UnconfirmedFact.MEASUREMENTS);
        }
        if (facts.origin() == null) {
            missing.add(UnconfirmedFact.ORIGIN);
        }
        if (facts.certifications().isEmpty()) {
            missing.add(UnconfirmedFact.CERTIFICATIONS);
        }
        return List.copyOf(missing);
    }

    /** 꼭 넣을 표현이 상품명 · 설명에 없거나, 쓰지 않을 표현이 어느 칸에든 있으면 경고한다(대소문자 무시). 거절하지 않는다 — 운영자가 검토에서 고친다. */
    private static List<Warning> warnings(Facts facts, Suggestion suggestion) {
        String main = (suggestion.name() + "\n" + suggestion.description()).toLowerCase(Locale.ROOT);
        List<String> all = new ArrayList<>(List.of(suggestion.name(), suggestion.description(), suggestion.seoTitle(), suggestion.seoDescription()));
        all.addAll(suggestion.searchTags());
        suggestion.imageAlts().forEach(alt -> all.add(alt.altText()));
        String everything = String.join("\n", all).toLowerCase(Locale.ROOT);
        List<Warning> warnings = new ArrayList<>();
        for (int index = 0; index < facts.mustInclude().size(); index++) {
            if (!main.contains(facts.mustInclude().get(index).toLowerCase(Locale.ROOT))) {
                warnings.add(new Warning("MUST_INCLUDE_MISSING", index));
            }
        }
        for (int index = 0; index < facts.mustAvoid().size(); index++) {
            if (everything.contains(facts.mustAvoid().get(index).toLowerCase(Locale.ROOT))) {
                warnings.add(new Warning("MUST_AVOID_USED", index));
            }
        }
        return List.copyOf(warnings);
    }

    private void list(List<String> values) {
        require(values.size() <= MAX_LIST);
        values.forEach(value -> fact(value, MAX_ITEM, false));
    }

    /** 운영자 입력 한 줄 칸 — product 가 이미 정규화했다(앞뒤 공백 없음 · 빈 값은 null). 제어 문자만 다시 본다. */
    private void fact(String value, int max, boolean nullable) {
        if (value == null) {
            require(nullable);
            return;
        }
        require(!value.isBlank() && value.length() <= max && !unsafe(value, false));
    }

    /** AI 출력 한 줄 칸 — 앞뒤 공백을 빼고, 링크 · 제어 문자를 받지 않는다. */
    private String singleLine(String value, int max, boolean nullable) {
        if (value == null) {
            require(nullable);
            return null;
        }
        String stripped = value.strip();
        require(!stripped.isEmpty() && stripped.length() <= max && !unsafe(stripped, false) && !stripped.contains("://"));
        return stripped;
    }

    /** 설명 — 개행만 더 받는다. {@code \r\n} 은 {@code \n} 으로 맞춘다. */
    private String text(String value) {
        require(value != null);
        String normalized = value.replace("\r\n", "\n").strip();
        require(!normalized.isEmpty() && normalized.length() <= DESCRIPTION_MAX && !normalized.contains("://") && !unsafe(normalized, true));
        return normalized;
    }

    /**
     * 제어 문자(C0 · DEL · C1) · 줄/문단 구분자 · 방향 제어 문자 · 태그 문자 · 짝 없는 서로게이트가 있는가 — product 초안 입력 검증과 같은 기준이다.
     * {@code allowLineBreaks} 면 {@code \n} 을 받는다(설명). 이모지 ZWJ · 변형 선택자는 받는다.
     */
    private static boolean unsafe(String value, boolean allowLineBreaks) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return value.codePoints().anyMatch(c -> !(allowLineBreaks && c == '\n') && (Character.isISOControl(c) || c == 0x2028 || c == 0x2029
            || c == 0x200E || c == 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069) || (c >= 0xE0000 && c <= 0xE007F)));
    }

    private static boolean magic(String contentType, byte[] bytes) {
        return switch (contentType) {
            case AiImage.JPEG -> bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
            case AiImage.PNG -> bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
            case AiImage.WEBP -> bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private JsonNode parse(String json, int maxBytes) {
        require(json != null && json.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
        try {
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            require(node != null && node.isObject());
            return node;
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    /** {@code Set.of(...).contains(null)} 은 NPE 다 — 빠진 칸도 계약 위반(고정 코드)으로 끝나게 먼저 거른다. */
    private static boolean oneOf(Set<String> allowed, String value) {
        return value != null && allowed.contains(value);
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private static JobExecutionException invalid() {
        return new JobExecutionException(INVALID_DATA, false);
    }
}
