package com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor;

import static com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationJobProcessorTest.JPEG;
import static com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationJobProcessorTest.PNG;
import static com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationJobProcessorTest.emptyFacts;
import static com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationJobProcessorTest.fullFacts;
import static com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationJobProcessorTest.input;
import static com.commercefoundry.domainlayer.productdraftgeneration.application.service.processor.ProductDraftGenerationJobProcessorTest.twoImages;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProductDraftGenerationValidationProcessorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ProductDraftGenerationValidationProcessor validation = new ProductDraftGenerationValidationProcessor(JSON);

    // ---- 입력 · 이미지 상한 ----

    @Test
    void eightImagesAtExactlyTwentyMebibytesInTotalAreAccepted() {
        List<Image> images = new ArrayList<>();
        long[] sizes = {AiImage.MAX_BYTES, AiImage.MAX_BYTES, AiImage.MAX_BYTES, AiImage.MAX_BYTES - 5, 1, 1, 1, 2};
        for (int index = 0; index < 8; index++) {
            images.add(new Image(index, "GALLERY", AiImage.WEBP, sizes[index], null, null));
        }
        assertThat(Arrays.stream(sizes).sum()).isEqualTo(ProductDraftGenerationValidationProcessor.MAX_TOTAL_IMAGE_BYTES).isEqualTo(20L * 1024 * 1024);

        assertThat(validation.input(7, input(fullFacts(), images)).images()).hasSize(8);
    }

    @Test
    void oneByteOverTheTotalIsTerminalTooLarge() {
        List<Image> images = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            images.add(new Image(index, "GALLERY", AiImage.JPEG, index < 4 ? AiImage.MAX_BYTES : 1, null, null));
        }
        failure("PRODUCT_DRAFT_IMAGES_TOO_LARGE", () -> validation.input(7, input(fullFacts(), images)));
    }

    static Stream<Arguments> invalidImageLists() {
        Image valid = new Image(0, "THUMBNAIL", AiImage.JPEG, 10, null, null);
        List<Image> nine = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            nine.add(new Image(index, "GALLERY", AiImage.JPEG, 10, null, null));
        }
        return Stream.of(
            Arguments.of("nine images", nine, 9),
            Arguments.of("count differs", List.of(valid), 2),
            Arguments.of("index gap", List.of(new Image(1, "THUMBNAIL", AiImage.JPEG, 10, null, null)), 1),
            Arguments.of("gif", List.of(new Image(0, "THUMBNAIL", AiImage.GIF, 10, null, null)), 1),
            Arguments.of("null content type", List.of(new Image(0, "THUMBNAIL", null, 10, null, null)), 1),
            Arguments.of("null image type", List.of(new Image(0, null, AiImage.JPEG, 10, null, null)), 1),
            Arguments.of("video type", List.of(new Image(0, "VIDEO", AiImage.JPEG, 10, null, null)), 1),
            Arguments.of("empty", List.of(new Image(0, "THUMBNAIL", AiImage.JPEG, 0, null, null)), 1),
            Arguments.of("over five mebibytes", List.of(new Image(0, "THUMBNAIL", AiImage.JPEG, AiImage.MAX_BYTES + 1L, null, null)), 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidImageLists")
    void imageMetadataOutsideTheContractIsInvalid(String name, List<Image> images, int count) {
        var input = new ProductDraftGenerationInputQueryResult(7, "REQUESTED", null, fullFacts(), count, images);
        failure("PRODUCT_DRAFT_INVALID_DATA", () -> validation.input(7, input));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"READY", "FAILED", "APPLIED", "REJECTED", "EXPIRED"})
    void onlyRequestedOrGeneratingDraftsAreGenerated(String status) {
        var input = new ProductDraftGenerationInputQueryResult(7, status, null, fullFacts(), 0, List.of());
        failure("PRODUCT_DRAFT_INVALID_DATA", () -> validation.input(7, input));
    }

    @Test
    void factsOutsideProductLimitsOrWithControlCharactersAreInvalid() {
        for (Facts facts : List.of(
            new Facts("a".repeat(101), List.of(), List.of(), null, null, List.of(), null, List.of(), List.of()),
            new Facts("셔츠‮", List.of(), List.of(), null, null, List.of(), null, List.of(), List.of()),
            new Facts(null, List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"), List.of(), null, null, List.of(), null, List.of(), List.of()),
            new Facts(null, List.of(), List.of("줄\n바꿈"), null, null, List.of(), null, List.of(), List.of()),
            new Facts(null, List.of(), List.of(), "가".repeat(1001), null, List.of(), null, List.of(), List.of()),
            new Facts(null, List.of(), List.of(), "총장\u0007", null, List.of(), null, List.of(), List.of()),
            new Facts(null, List.of(), List.of(), null, " ", List.of(), null, List.of(), List.of()),
            new Facts(null, List.of(), List.of(), null, null, List.of(), "t".repeat(201), List.of(), List.of()),
            new Facts(null, List.of(), List.of(), null, null, List.of(), null, List.of("󠁁"), List.of()))) {
            failure("PRODUCT_DRAFT_INVALID_DATA", () -> validation.input(7, input(facts, List.of())));
        }
    }

    @Test
    void measurementsNoteKeepsLineBreaksTabsAndEmojiVariationSelectors() {
        var facts = new Facts("셔츠 ❤️", List.of(), List.of(), "총장 70\r\n가슴\t50", null, List.of(), null, List.of(), List.of());
        var prepared = validation.input(7, input(facts, List.of()));
        assertThat(JSON.readTree(prepared.factsJson()).path("measurementsNote").stringValue()).isEqualTo("총장 70\r\n가슴\t50");
    }

    // ---- 이미지 바이트 ----

    @Test
    void imageBytesMustMatchPinnedLengthTypeAndMagic() {
        var prepared = validation.input(7, input(fullFacts(), twoImages()));
        var jpeg = new ProductDraftGenerationImageQueryResult(0, AiImage.JPEG, JPEG);
        var png = new ProductDraftGenerationImageQueryResult(1, AiImage.PNG, PNG);
        assertThat(validation.images(prepared, List.of(jpeg, png))).containsExactly(jpeg, png);

        byte[] fakePng = PNG.clone();
        fakePng[1] = 'X';
        for (List<ProductDraftGenerationImageQueryResult> received : List.of(
            List.of(jpeg),
            List.of(jpeg, new ProductDraftGenerationImageQueryResult(1, AiImage.PNG, fakePng)),
            List.of(jpeg, new ProductDraftGenerationImageQueryResult(1, AiImage.JPEG, PNG)),
            List.of(jpeg, new ProductDraftGenerationImageQueryResult(1, AiImage.PNG, new byte[0])),
            List.of(png, jpeg))) {
            failure("PRODUCT_DRAFT_IMAGE_CHANGED", () -> validation.images(prepared, received));
        }
    }

    // ---- AI 출력 ----

    @Test
    void outputIsNormalizedAndWarningsUseIndexesOnly() {
        var facts = new Facts(null, List.of(), List.of(), null, null, List.of(), null, List.of("린넨", "여름"), List.of("최저가", "무료"));
        var prepared = validation.input(7, input(facts, List.of()));
        var candidate = new ProductDraftGenerationCandidate("  린넨 셔츠  ", "첫 줄\r\n둘째 줄", List.of("Shirt", "shirt", "  ", "최저가 셔츠"), "린넨 셔츠",
            "가벼운 셔츠", null);

        JsonNode result = JSON.readTree(validation.outcome(candidate, prepared));

        assertThat(result.path("suggestion").path("name").stringValue()).isEqualTo("린넨 셔츠");
        assertThat(result.path("suggestion").path("description").stringValue()).isEqualTo("첫 줄\n둘째 줄");
        assertThat(result.path("suggestion").path("searchTags").toString()).isEqualTo("[\"Shirt\",\"최저가 셔츠\"]");
        assertThat(result.path("suggestion").path("imageAlts").toString()).isEqualTo("[]");
        assertThat(result.path("warnings").toString())
            .isEqualTo("[{\"code\":\"MUST_INCLUDE_MISSING\",\"index\":1},{\"code\":\"MUST_AVOID_USED\",\"index\":0}]");
        assertThat(result.path("unconfirmedFacts")).hasSize(5);
    }

    static Stream<Arguments> invalidOutputs() {
        List<ImageAlt> alts = List.of(new ImageAlt(0, "앞"), new ImageAlt(1, "뒤"));
        return Stream.of(
            Arguments.of("null name", new ProductDraftGenerationCandidate(null, "설명", List.of(), "t", "d", alts)),
            Arguments.of("long name", new ProductDraftGenerationCandidate("a".repeat(101), "설명", List.of(), "t", "d", alts)),
            Arguments.of("multiline name", new ProductDraftGenerationCandidate("셔츠\n입니다", "설명", List.of(), "t", "d", alts)),
            Arguments.of("link", new ProductDraftGenerationCandidate("셔츠", "https://example.invalid 에서 구매", List.of(), "t", "d", alts)),
            Arguments.of("long description", new ProductDraftGenerationCandidate("셔츠", "a".repeat(4001), List.of(), "t", "d", alts)),
            Arguments.of("bidi control", new ProductDraftGenerationCandidate("셔츠", "설명‮", List.of(), "t", "d", alts)),
            Arguments.of("long tag", new ProductDraftGenerationCandidate("셔츠", "설명", List.of("a".repeat(31)), "t", "d", alts)),
            Arguments.of("too many tags", new ProductDraftGenerationCandidate("셔츠", "설명", tags(21), "t", "d", alts)),
            Arguments.of("long seo title", new ProductDraftGenerationCandidate("셔츠", "설명", List.of(), "a".repeat(71), "d", alts)),
            Arguments.of("long seo description", new ProductDraftGenerationCandidate("셔츠", "설명", List.of(), "t", "a".repeat(161), alts)),
            Arguments.of("missing alt", new ProductDraftGenerationCandidate("셔츠", "설명", List.of(), "t", "d", List.of(new ImageAlt(0, "앞")))),
            Arguments.of("duplicate alt", new ProductDraftGenerationCandidate("셔츠", "설명", List.of(), "t", "d", List.of(new ImageAlt(0, "앞"), new ImageAlt(0, "뒤")))),
            Arguments.of("alt out of range", new ProductDraftGenerationCandidate("셔츠", "설명", List.of(), "t", "d", List.of(new ImageAlt(0, "앞"), new ImageAlt(2, "뒤")))),
            Arguments.of("long alt", new ProductDraftGenerationCandidate("셔츠", "설명", List.of(), "t", "d", List.of(new ImageAlt(0, "앞"), new ImageAlt(1, "a".repeat(201))))),
            Arguments.of("null candidate", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOutputs")
    void outputOutsideTheContractIsTerminalInvalidData(String name, ProductDraftGenerationCandidate candidate) {
        var prepared = validation.input(7, input(fullFacts(), twoImages()));
        failure("PRODUCT_DRAFT_INVALID_DATA", () -> validation.outcome(candidate, prepared));
    }

    @Test
    void largestValidOutputFitsTheResultLimit() {
        List<ImageAlt> alts = new ArrayList<>();
        List<Image> images = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            alts.add(new ImageAlt(index, "가".repeat(200)));
            images.add(new Image(index, "GALLERY", AiImage.JPEG, 10, null, null));
        }
        var prepared = validation.input(7, input(emptyFacts(), images));
        var candidate = new ProductDraftGenerationCandidate("가".repeat(100), "가".repeat(4000), tags(20).stream().map(tag -> tag + "가".repeat(20)).toList(),
            "가".repeat(70), "가".repeat(160), alts);

        String json = validation.outcome(candidate, prepared);

        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(ProductDraftGenerationValidationProcessor.MAX_RESULT_BYTES);
    }

    private static List<String> tags(int count) {
        List<String> tags = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            tags.add("tag" + index);
        }
        return tags;
    }

    private static void failure(String code, Executable call) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(JobExecutionException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(code);
            assertThat(failure.isRetryable()).isFalse();
            assertThat(failure.getMessage()).isNull();
        });
    }
}
