package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import com.commercefoundry.domainlayer.job.domain.model.JobExecutionException;
import com.commercefoundry.domainlayer.productdraftgeneration.application.model.ProductDraftGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationAiPort.Attachment;
import com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.ProductDraftGenerationInputPort.ImageRequest;
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
import java.util.regex.Pattern;
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
 *   <li><b>요청 크기</b> — 이미지는 {@link #MAX_IMAGE_COUNT}(8)장 · 장당 {@link #MAX_IMAGE_BYTES}(5MiB)이고, 원본 합계는 {@link #MAX_TOTAL_IMAGE_BYTES}(20MiB)다.
 *       ai-core 는 장수 · 장당 크기만 보고 요청 전체 크기는 보지 않는다 — Messages API 요청 본문 상한(32MB, 넘으면 413)에서 base64 4/3 배(약 26.7MB)를
 *       빼고 남는 몫을 프롬프트 · JSON 여유분으로 둔다. 바이트를 받기 전에 메타데이터 {@code byteLength} 합계로 막고(재시도 불가), 받으면서도 장마다 누적을 다시 본다.</li>
 *   <li><b>이미지 바이트</b> — 장을 받을 때마다 순번 · 크기 · magic bytes 가 고정 값과 다르면 바뀐 이미지로 본다(product 410 {@code PRODUCT_DRAFT_044} 와 같은 코드).
 *       응답 헤더 · 본문 상한은 입력 어댑터가 먼저 본다.</li>
 *   <li><b>출력</b> — AI 출력은 불신 입력이다. 길이 · 줄바꿈 · 제어 문자 · 링크({@code ://} · {@code www.}) · 꺾쇠({@code <} {@code >}) · 이미지 순번을 다시 보고,
 *       확인이 필요한 속성 · 경고는 서버가 운영자 입력으로 계산한다. 이메일 · 전화번호는 여기서 막지 않는다 — product 가 반영할 때(PR-3) sanitize 를 거친다.
 *       결과에 운영자 입력 사실을 다시 싣지 않는다 — 사실의 정본은 product 초안이다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationValidationProcessor {

    /** 이미지 장수 상한. ai-core {@code AiImage.MAX_COUNT} 와 같다(같은지는 AI 어댑터 테스트가 본다). */
    public static final int MAX_IMAGE_COUNT = 8;
    /** 장당 바이트 상한. ai-core {@code AiImage.MAX_BYTES}(API 의 5MiB)와 같다. */
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    /** 이미지 원본 합계 상한. 근거는 클래스 설명. */
    public static final long MAX_TOTAL_IMAGE_BYTES = 20L * 1024 * 1024;
    /** 결과 JSON 상한 — 엔진 상한(64KiB)보다 작게 둔다. */
    public static final int MAX_RESULT_BYTES = 32 * 1024;

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String WEBP = "image/webp";
    /** product 가 고정하는 형식(JPEG · PNG · WebP). GIF 는 product 가 받지 않는다. ai-core 가 받는 형식 안이다. */
    public static final Set<String> IMAGE_CONTENT_TYPES = Set.of(JPEG, PNG, WEBP);

    public static final String INVALID_DATA = "PRODUCT_DRAFT_INVALID_DATA";
    public static final String INPUT_MISMATCH = "PRODUCT_DRAFT_INPUT_MISMATCH";
    public static final String IMAGES_TOO_LARGE = "PRODUCT_DRAFT_IMAGES_TOO_LARGE";
    /** 입력 어댑터의 바뀐 이미지와 같은 코드다 — 운영자 안내가 하나다. */
    public static final String IMAGE_CHANGED = Code.PRODUCT_DRAFT_IMAGE_CHANGED.name();

    private static final Set<String> STATUSES = Set.of("REQUESTED", "GENERATING");
    private static final Set<String> IMAGE_TYPES = Set.of("THUMBNAIL", "GALLERY");
    /** magic bytes 판별에 필요한 앞부분 길이(WebP 12바이트). */
    private static final int MAGIC_BYTES = 12;
    /** {@code www.} 로 시작하는 토큰 — 앞이 영문 · 숫자가 아니면 링크로 본다(대소문자 무시). 한글 바로 뒤에 붙인 {@code 는www.} 도 잡는다. */
    private static final Pattern BARE_LINK = Pattern.compile("(?<![A-Za-z0-9])www\\.", Pattern.CASE_INSENSITIVE);
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
        require(input.imageCount() == images.size() && images.size() <= MAX_IMAGE_COUNT);
        long total = 0;
        for (int index = 0; index < images.size(); index++) {
            Image image = images.get(index);
            require(image != null && image.index() == index && oneOf(IMAGE_TYPES, image.imageType()) && oneOf(IMAGE_CONTENT_TYPES, image.contentType())
                && image.byteLength() >= 1 && image.byteLength() <= MAX_IMAGE_BYTES);
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

    /** 입력 포트에 넘길 장별 기대 값(순번 · 길이 · 형식). {@link #input} 을 지난 메타데이터라 길이는 장당 상한 안이다. */
    public List<ImageRequest> imageRequests(Prepared prepared) {
        return prepared.images().stream().map(image -> new ImageRequest(image.index(), Math.toIntExact(image.byteLength()), image.contentType())).toList();
    }

    /**
     * 방금 받은 한 장을 본다 — 다음 장을 읽기 전에 부른다. 순번 · 크기 · magic bytes 가 고정 값과 다르면 {@code IMAGE_CHANGED},
     * 이미 받은 장과 합친 바이트가 {@link #MAX_TOTAL_IMAGE_BYTES} 를 넘으면 {@code IMAGES_TOO_LARGE}(둘 다 재시도 불가).
     * 형식은 응답 헤더가 아니라 magic bytes 로 확인한 고정 메타데이터 형식이다.
     *
     * @param accepted 이미 받아 통과한 장(순서대로). 이 장의 자리는 {@code accepted.size()} 다
     */
    public Attachment image(Prepared prepared, List<Attachment> accepted, ProductDraftGenerationImageQueryResult received) {
        List<Image> expected = prepared.images();
        int position = accepted.size();
        if (received == null || position >= expected.size()) {
            throw new JobExecutionException(IMAGE_CHANGED, false);
        }
        Image image = expected.get(position);
        if (received.index() != image.index() || received.length() != image.byteLength() || !magic(image.contentType(), received.head(MAGIC_BYTES))) {
            throw new JobExecutionException(IMAGE_CHANGED, false);
        }
        long total = received.length();
        for (Attachment previous : accepted) {
            total += previous.image().length();
        }
        if (total > MAX_TOTAL_IMAGE_BYTES) {
            throw new JobExecutionException(IMAGES_TOO_LARGE, false);
        }
        return new Attachment(image.contentType(), received);
    }

    /** 고정한 장을 모두 받았는가. 모자라면 {@code IMAGE_CHANGED}(재시도 불가). */
    public List<Attachment> images(Prepared prepared, List<Attachment> accepted) {
        if (accepted.size() != prepared.images().size()) {
            throw new JobExecutionException(IMAGE_CHANGED, false);
        }
        return List.copyOf(accepted);
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

    /** AI 출력 한 줄 칸 — 앞뒤 공백을 빼고, 링크 · 꺾쇠 · 제어 문자를 받지 않는다. */
    private String singleLine(String value, int max, boolean nullable) {
        if (value == null) {
            require(nullable);
            return null;
        }
        String stripped = value.strip();
        require(!stripped.isEmpty() && stripped.length() <= max && !unsafe(stripped, false) && !markupOrLink(stripped));
        return stripped;
    }

    /** 설명 — 개행만 더 받는다. {@code \r\n} 은 {@code \n} 으로 맞춘다. */
    private String text(String value) {
        require(value != null);
        String normalized = value.replace("\r\n", "\n").strip();
        require(!normalized.isEmpty() && normalized.length() <= DESCRIPTION_MAX && !markupOrLink(normalized) && !unsafe(normalized, true));
        return normalized;
    }

    /**
     * 링크({@code ://} · {@code www.} 로 시작하는 토큰)나 꺾쇠({@code <} · {@code >})가 있는가. 꺾쇠는 문구에 필요 없고 HTML · 스크립트 조각의 신호라 막는다.
     * 이메일 · 전화번호는 보지 않는다 — product 가 반영할 때(PR-3) sanitize 를 반드시 거친다.
     */
    private static boolean markupOrLink(String value) {
        return value.indexOf('<') >= 0 || value.indexOf('>') >= 0 || value.contains("://") || BARE_LINK.matcher(value).find();
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
            case JPEG -> bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
            case PNG -> bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
            case WEBP -> bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
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
