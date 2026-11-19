package com.commercefoundry.domainlayer.productdraft.domain.model;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 운영자 사실과 분리된 검증·정제 결과다. */
public record ProductDraftCandidate(Suggestion suggestion, List<UnconfirmedFact> unconfirmedFacts, List<Warning> warnings) {

    public ProductDraftCandidate {
        unconfirmedFacts = List.copyOf(unconfirmedFacts);
        warnings = List.copyOf(warnings);
    }

    /** 표시되는 정제 문구로 경고를 계산한다. 경고에는 운영자 입력 문구의 순번만 담는다. */
    public ProductDraftCandidate withWarningsFor(ProductDraftFacts facts) {
        String main = normalized(suggestion.name() + "\n" + suggestion.description());
        List<String> all = new ArrayList<>(List.of(main, suggestion.seoTitle(), suggestion.seoDescription()));
        all.addAll(suggestion.searchTags());
        suggestion.imageAlts().forEach(alt -> all.add(alt.altText()));
        String everything = normalized(String.join("\n", all));
        List<Warning> calculated = new ArrayList<>();
        for (int index = 0; index < facts.mustInclude().size(); index++) {
            if (!main.contains(normalized(facts.mustInclude().get(index)))) {
                calculated.add(new Warning(WarningCode.MUST_INCLUDE_MISSING, index));
            }
        }
        for (int index = 0; index < facts.mustAvoid().size(); index++) {
            if (everything.contains(normalized(facts.mustAvoid().get(index)))) {
                calculated.add(new Warning(WarningCode.MUST_AVOID_USED, index));
            }
        }
        return new ProductDraftCandidate(suggestion, unconfirmedFacts, calculated);
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "ProductDraftCandidate[redacted]";
    }

    public enum UnconfirmedFact {
        MATERIALS, CARE_NOTES, MEASUREMENTS, ORIGIN, CERTIFICATIONS
    }

    public enum WarningCode {
        MUST_INCLUDE_MISSING, MUST_AVOID_USED
    }

    public record Suggestion(
        String name, String description, List<String> searchTags, String seoTitle, String seoDescription, List<ImageAlt> imageAlts
    ) {

        public Suggestion {
            searchTags = List.copyOf(searchTags);
            imageAlts = List.copyOf(imageAlts);
        }

        @Override
        public String toString() {
            return "Suggestion[redacted]";
        }
    }

    public record ImageAlt(int index, String altText) {

        @Override
        public String toString() {
            return "ImageAlt[redacted]";
        }
    }

    public record Warning(WarningCode code, int index) {
    }

}
