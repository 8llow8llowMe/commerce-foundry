package com.commercefoundry.domainlayer.productdraft.adapter.out.client.support;

import static com.commercefoundry.domainlayer.productdraft.adapter.out.client.support.ProductDraftGenerationResultResponseSupport.JSON;
import static com.commercefoundry.domainlayer.productdraft.adapter.out.client.support.ProductDraftGenerationResultResponseSupport.MAX_RESULT_BYTES;
import static com.commercefoundry.domainlayer.productdraft.adapter.out.client.support.ProductDraftGenerationResultResponseSupport.require;

import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate.ImageAlt;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate.Suggestion;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate.UnconfirmedFact;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate.Warning;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate.WarningCode;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** 상품 초안 제안만 읽는다. 운영자 사실을 덮어쓰는 필드나 AI 원문을 경계 밖으로 전달하지 않는다. */
final class ProductDraftGenerationCandidateReader {

    private static final PiiMasker MASKER = new PiiMasker(Map.of());
    private static final Pattern BARE_LINK = Pattern.compile("(?<![A-Za-z0-9])www\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMOJI_FORMAT = Pattern.compile("[\\x{200D}\\x{FE00}-\\x{FE0F}\\x{E0100}-\\x{E01EF}]");

    private ProductDraftGenerationCandidateReader() {
    }

    static ProductDraftCandidate read(ProductDraft draft, String json) {
        require(json.getBytes(StandardCharsets.UTF_8).length <= MAX_RESULT_BYTES);
        JsonNode root = JSON.readTree(json);
        require(root.isObject() && root.size() == 3);
        JsonNode suggestion = root.path("suggestion");
        require(suggestion.isObject() && suggestion.size() == 6);
        Suggestion candidate = new Suggestion(
            text(suggestion.path("name"), 100, false), text(suggestion.path("description"), 4000, true),
            tags(suggestion.path("searchTags")), text(suggestion.path("seoTitle"), 70, false),
            text(suggestion.path("seoDescription"), 160, false), alts(suggestion.path("imageAlts"), draft.images().size())
        );
        return new ProductDraftCandidate(candidate, facts(root.path("unconfirmedFacts"), draft.facts()), warnings(root.path("warnings"), draft.facts()));
    }

    private static List<String> tags(JsonNode tags) {
        require(tags.isArray() && tags.size() <= 20);
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (JsonNode tag : tags) {
            String cleaned = text(tag, 30, false);
            if (seen.add(cleaned.toLowerCase(Locale.ROOT))) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static List<ImageAlt> alts(JsonNode alts, int imageCount) {
        require(alts.isArray() && alts.size() == imageCount && imageCount <= 8);
        Set<Integer> seen = new HashSet<>();
        List<ImageAlt> results = new ArrayList<>();
        for (JsonNode alt : alts) {
            require(alt.isObject() && alt.size() == 2);
            int index = index(alt.path("index"), imageCount);
            require(seen.add(index));
            results.add(new ImageAlt(index, text(alt.path("altText"), 200, false)));
        }
        results.sort(Comparator.comparingInt(ImageAlt::index));
        return results;
    }

    private static List<UnconfirmedFact> facts(JsonNode nodes, ProductDraftFacts facts) {
        EnumSet<UnconfirmedFact> expected = EnumSet.allOf(UnconfirmedFact.class);
        if (!facts.materials().isEmpty()) {
            expected.remove(UnconfirmedFact.MATERIALS);
        }
        if (!facts.careNotes().isEmpty()) {
            expected.remove(UnconfirmedFact.CARE_NOTES);
        }
        if (facts.measurementsNote() != null) {
            expected.remove(UnconfirmedFact.MEASUREMENTS);
        }
        if (facts.origin() != null) {
            expected.remove(UnconfirmedFact.ORIGIN);
        }
        if (!facts.certifications().isEmpty()) {
            expected.remove(UnconfirmedFact.CERTIFICATIONS);
        }
        require(nodes.isArray() && nodes.size() == expected.size());
        EnumSet<UnconfirmedFact> received = EnumSet.noneOf(UnconfirmedFact.class);
        for (JsonNode node : nodes) {
            require(received.add(UnconfirmedFact.valueOf(node.stringValue(""))));
        }
        require(received.equals(expected));
        return List.copyOf(expected);
    }

    private static List<Warning> warnings(JsonNode nodes, ProductDraftFacts facts) {
        require(nodes.isArray() && nodes.size() <= facts.mustInclude().size() + facts.mustAvoid().size());
        Set<Warning> seen = new HashSet<>();
        List<Warning> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            require(node.isObject() && node.size() == 2);
            WarningCode code = WarningCode.valueOf(node.path("code").stringValue(""));
            int size = code == WarningCode.MUST_INCLUDE_MISSING ? facts.mustInclude().size() : facts.mustAvoid().size();
            Warning warning = new Warning(code, index(node.path("index"), size));
            require(seen.add(warning));
            result.add(warning);
        }
        return result;
    }

    private static int index(JsonNode node, int size) {
        require(node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0 && node.intValue() < size);
        return node.intValue();
    }

    private static String text(JsonNode node, int maxLength, boolean allowNewline) {
        require(node.isString());
        String raw = node.stringValue();
        require(safe(raw, maxLength, allowNewline));
        String cleaned = MASKER.mask(raw, List.of()).strip();
        // 정규화로 전각 꺾쇠나 링크가 드러나거나 마스킹 뒤 길이가 늘 수 있다.
        require(safe(cleaned, maxLength, allowNewline));
        // 이모지 조합 문자는 보존하되 개인정보 패턴을 가르는 데 쓰였으면 결과를 받지 않는다.
        String withoutEmojiFormat = EMOJI_FORMAT.matcher(cleaned).replaceAll("");
        require(cleaned.equals(withoutEmojiFormat) || (safe(withoutEmojiFormat, maxLength, allowNewline)
            && withoutEmojiFormat.equals(MASKER.mask(withoutEmojiFormat, List.of()))));
        return cleaned;
    }

    private static boolean safe(String value, int maxLength, boolean allowNewline) {
        return !value.isBlank() && value.length() <= maxLength
            && value.codePoints().noneMatch(code -> unsafe(code, allowNewline))
            && value.indexOf('<') < 0 && value.indexOf('>') < 0 && !value.contains("://") && !BARE_LINK.matcher(value).find();
    }

    private static boolean unsafe(int code, boolean allowNewline) {
        return !(allowNewline && code == '\n') && (Character.isISOControl(code) || (code >= 0xD800 && code <= 0xDFFF)
            || code == 0x2028 || code == 0x2029 || (code != 0x200D && Character.getType(code) == Character.FORMAT));
    }
}
