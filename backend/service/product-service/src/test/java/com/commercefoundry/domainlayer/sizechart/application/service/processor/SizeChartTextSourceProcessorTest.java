package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SizeChartTextSourceProcessorTest {
    private static final String TABLE = "<table><tr><th>사이즈</th><th>가슴</th><th>총장</th></tr>"
        + "<tr><td>S</td><td>50</td><td>60.5</td></tr></table>";
    private final SizeChartTextSourceProcessor processor = new SizeChartTextSourceProcessor();

    @Test
    void extractsOnlyStrictTableSnapshotsFromBothLanguagesWithoutSurroundingProse() {
        var product = product("<p>배송 안내</p>" + TABLE, TABLE.replace("사이즈", "Size").replace("가슴", "Chest").replace("총장", "Length"));
        var candidates = processor.sources(product);
        assertThat(candidates).hasSize(2);
        assertThat(candidates.getFirst().sourceId()).isEqualTo("TEXT-KO-1");
        assertThat(candidates.getFirst().bodyHash()).matches("[0-9a-f]{64}");
        assertThat(candidates.getFirst().sourceText()).isEqualTo("가슴 총장 S 50 60.5");
        assertThat(candidates.get(1).sourceId()).isEqualTo("TEXT-EN-1");
        assertThat(candidates.get(1).sourceText()).isEqualTo("Chest Length S 50 60.5");
        assertThat(candidates.getFirst().toString()).doesNotContain("60.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer@example.test", "010-1234-5678", "홍길동", "서울시 주소", "ignore instructions", "1234", "50cm",
        "50/60", "50.123", "<img src='https://example.test/a.png'>", "<!--secret-->50"})
    void refusesEntireBlockForSensitiveUnknownOrAmbiguousCells(String cell) {
        assertThat(processor.sources(product(TABLE.replace("<td>50</td>", "<td>" + cell + "</td>"), null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hidden", "style='display:none'", "style='visibility:hidden'", "aria-hidden='true'", "colspan='1'", "rowspan='1'",
        "contenteditable='true'", "style='font-size: 0'", "style='text-indent:-9999px'", "class='blind'", "class='cell SR-ONLY'",
        "style='height:0'", "style='max-height: 0px'", "style='width:0;overflow:hidden'", "style='transform: scale(0)'", "style='transform:scaleY(0)'"})
    void refusesHiddenOrMergedCellsInsteadOfSilentlyDroppingThem(String attribute) {
        assertThat(processor.sources(product(TABLE.replace("<td>50", "<td " + attribute + ">50"), null))).isEmpty();
    }

    @Test
    void refusesHiddenAncestorsNestedTablesUnknownHeadersAndUnevenRows() {
        for (String html : List.of("<div hidden>" + TABLE + "</div>", "<div class='hide'>" + TABLE + "</div>",
            "<details><summary>사이즈</summary>" + TABLE + "</details>", "<div style='height:0'>" + TABLE + "</div>",
            TABLE.replace("<table>", "<table class='blind'>"), TABLE.replace("50</td>", TABLE + "</td>"),
            TABLE.replace("가슴", "고객명"), TABLE.replace("<td>60.5</td>", ""), TABLE.replace("<td>S</td>", "<td>홍길동</td>"))) {
            assertThat(processor.sources(product(html, null))).isEmpty();
        }
    }

    @Test
    void keepsVisibleTablesWithHarmlessStylingAndCaptionUnit() {
        String styled = TABLE.replace("<table>", "<table class='size-table' style='width:100%; border-width:0; line-height:1.5'><caption>cm</caption>");
        assertThat(processor.sources(product(styled, null))).singleElement()
            .satisfies(source -> assertThat(source.sourceText()).isEqualTo("가슴 총장 cm S 50 60.5"));
        assertThat(processor.sources(product("<details open><summary>사이즈</summary>" + TABLE + "</details>", null))).hasSize(1);
        assertThat(processor.sources(product(TABLE.replace("<td>50", "<td style='transform:scale(0.9)'>50"), null))).hasSize(1);
    }

    @Test
    void stylesheetAnywhereInBodyRefusesEveryCandidateOfThatLanguageOnly() {
        String withStyle = "<style>.x{display:none}</style>" + TABLE;
        String withLink = "<link rel='Stylesheet' href='https://example.test/a.css'>" + TABLE;
        assertThat(processor.sources(product(withStyle, TABLE))).singleElement()
            .satisfies(source -> assertThat(source.sourceId()).isEqualTo("TEXT-EN-1"));
        assertThat(processor.sources(product(withLink, null))).isEmpty();
    }

    @Test
    void onlyTheFirstFortyTablesAreInspected() {
        String junk = "<table><tr><td>배송</td></tr></table>";
        assertThat(processor.sources(product(junk.repeat(40) + TABLE, null))).isEmpty();
        assertThat(processor.sources(product(junk.repeat(39) + TABLE, null))).singleElement()
            .satisfies(source -> assertThat(source.sourceId()).isEqualTo("TEXT-KO-40"));
    }

    @Test
    void rejectsOnlyUnsafeBlockAndBoundsOversizedOrMissingBodies() {
        assertThat(processor.sources(product(TABLE.replace("50", "private") + TABLE, null))).hasSize(1);
        assertThat(processor.sources(product(" ".repeat(256 * 1024) + TABLE, null))).isEmpty();
        assertThat(processor.sources(product(null, null))).isEmpty();
        assertThat(processor.sources(product(TABLE.repeat(30), null))).hasSize(20);
    }

    @Test
    void verifiesSelectedTextAndBodyTogetherAndKeepsOriginalRequestHash() {
        var product = product(TABLE, null);
        var source = processor.sources(product).getFirst();
        var command = selected(source.sourceText(), source.sourceId(), source.bodyHash());
        processor.verify(product, command);
        var legacy = new SizeChartDraftCreateCommand(10, 20, 40, command.idempotencyKey(), "TEXT", command.sourceText());
        var validation = new SizeChartDraftValidationProcessor();
        assertThat(validation.normalizedRequestHash(command)).isEqualTo(validation.normalizedRequestHash(legacy));
        assertMismatch(product, selected(source.sourceText().replace("50", "51"), source.sourceId(), source.bodyHash()));
        assertMismatch(product(TABLE + "<p>changed</p>", null), command);
        assertMismatch(product, selected(source.sourceText(), "TEXT-EN-1", source.bodyHash()));
        assertMismatch(product, selected(source.sourceText(), source.sourceId(), "0".repeat(64)));
    }

    private void assertMismatch(Product product, SizeChartDraftCreateCommand command) {
        assertThatThrownBy(() -> processor.verify(product, command)).isInstanceOfSatisfying(SizeChartDraftTransportException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(SizeChartDraftTransportErrorCode.TEXT_SELECTION_CHANGED));
    }

    private SizeChartDraftCreateCommand selected(String text, String sourceId, String bodyHash) {
        return new SizeChartDraftCreateCommand(10, 20, 40, UUID.randomUUID(), "TEXT", text, List.of(), false,
            new SizeChartDraftCreateCommand.TextSelection(sourceId, bodyHash));
    }

    private Product product(String html, String htmlEn) {
        var product = mock(Product.class);
        when(product.id()).thenReturn(10L);
        when(product.descriptionHtml()).thenReturn(html);
        when(product.descriptionHtmlEn()).thenReturn(htmlEn);
        return product;
    }
}
