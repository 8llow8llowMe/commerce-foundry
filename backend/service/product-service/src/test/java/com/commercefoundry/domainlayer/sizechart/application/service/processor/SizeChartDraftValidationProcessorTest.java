package com.commercefoundry.domainlayer.sizechart.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartDraftCreateCommand;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplate;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartTemplateColumn;
import com.commercefoundry.domainlayer.sizechart.domain.model.SizeChartUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SizeChartDraftValidationProcessorTest {
    private final SizeChartDraftValidationProcessor validation = new SizeChartDraftValidationProcessor();
    private final SizeChartTemplate template = new SizeChartTemplate(20, "T", SizeChartUnit.CM, 3);
    private final List<SizeChartTemplateColumn> columns = List.of(new SizeChartTemplateColumn(30, 20, "Chest", null, 0));

    @Test
    void onlySelectedTemplateMeasurementsAreAccepted() {
        var korean = List.of(new SizeChartTemplateColumn(30, 20, "가슴", null, 0),
            new SizeChartTemplateColumn(31, 20, "총장", null, 1));
        assertThat(validation.template(template, korean)).contains("가슴", "총장");
        assertThat(validation.source(command("S 가슴 50 총장 70"), template, korean)).contains("S 가슴 50 총장 70");
        assertThat(validation.source(command("S CHEST 50"), template, columns)).contains("CHEST 50");
        assertThatThrownBy(() -> validation.source(command("S CHEST 50 Total Length 70"), template, columns))
            .isInstanceOf(SizeChartDraftTransportException.class);
        assertThatThrownBy(() -> validation.source(command("S Waist 50"), template, columns))
            .isInstanceOf(SizeChartDraftTransportException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice", "Alice Smith", "홍길동", "서울 강남구", "Maple Avenue", "Chest Alice", "Chest/Name", "Chest ", "Ｃｈｅｓｔ"})
    void customTemplateNamesCannotProveSourceSafety(String name) {
        var unsafe = List.of(new SizeChartTemplateColumn(30, 20, name, null, 0));
        assertThatThrownBy(() -> validation.template(template, unsafe)).isInstanceOf(SizeChartDraftTransportException.class).hasNoCause();
        assertThatThrownBy(() -> validation.source(command("S " + name + " 50"), template, unsafe))
            .isInstanceOf(SizeChartDraftTransportException.class).hasNoCause();
    }

    @Test
    void normalizationHasStableHashAndCommandNeverPrintsSource() {
        var first = command("S   50 cm");
        var second = command("S 50 cm");
        assertThat(validation.normalizedRequestHash(first)).isEqualTo(validation.normalizedRequestHash(second));
        assertThat(first.toString()).doesNotContain("50");
        assertThat(validation.normalizedRequestHash(first)).isNotEqualTo(validation.normalizedRequestHash(command("S 51 cm")));
    }

    @Test
    void knownPiiIsMaskedThenRemovedBeforeStorageAndNeverAppearsInErrors() {
        String text = "S Chest 50cm sample@example.invalid 010-1234-5678 " + "\uac15\ub0a8\ub85c 123";
        String source = validation.source(command(text), template, columns);
        assertThat(source).contains("S Chest 50 cm").doesNotContain("example", "010", "123", "[EMAIL]", "[PHONE]", "[ADDRESS]");
        assertThatThrownBy(() -> validation.source(command(text + " unknown-person"), template, columns))
            .isInstanceOf(SizeChartDraftTransportException.class).hasMessageNotContaining("unknown-person").hasCause(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"S 50 Alice", "S 50 https://example.invalid", "S 50 <script>", "S 50 inch", "S 50\u0000", "just prose", "S ChestOnly 50",
        "S 가슴 50 홍길동", "S 가슴 50 서울 강남구", "S Chest 50 ship to Maple Avenue"})
    void unsafeProseAndWrongUnitsFailClosed(String text) {
        assertThatThrownBy(() -> validation.source(command(text), template, columns)).isInstanceOf(SizeChartDraftTransportException.class);
    }

    @Test
    void unboundedTextFailsBeforeMasking() {
        assertThatThrownBy(() -> validation.normalizedRequestHash(command("S 50 ".repeat(3000))))
            .isInstanceOf(SizeChartDraftTransportException.class);
    }

    @Test
    void candidateRebuildsOnlyStrictSafeSchema() {
        String json = """
            {"unit":"CM","rows":[{"sizeLabel":"s","cells":[{"columnId":30,"value":50.25}]}],"warnings":[]}
            """;
        var candidate = validation.candidate(json, validation.template(template, columns));
        assertThat(candidate.json()).contains("\"sizeLabel\":\"S\"", "50.25");
        assertThat(candidate.toString()).doesNotContain("50.25");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[]", "{} {}", "{\"unit\":\"CM\",\"unit\":\"CM\",\"rows\":[],\"warnings\":[]}",
        "{\"unit\":\"CM\",\"rows\":[],\"warnings\":[],\"rawProviderOutput\":\"private\"}",
        "{\"unit\":\"CM\",\"rows\":[{\"sizeLabel\":\"Alice\",\"cells\":[]}],\"warnings\":[]}",
        "{\"unit\":\"CM\",\"rows\":[{\"sizeLabel\":\"S\",\"cells\":[{\"columnId\":999,\"value\":1}]}],\"warnings\":[]}",
        "{\"unit\":\"CM\",\"rows\":[{\"sizeLabel\":\"S\",\"cells\":[{\"columnId\":30,\"value\":-1}]}],\"warnings\":[]}",
        "{\"unit\":\"CM\",\"rows\":[{\"sizeLabel\":\"S\",\"cells\":[{\"columnId\":30,\"value\":1e99999999}]}],\"warnings\":[]}"
    })
    void malformedCandidateNeverLeaksParserDiagnostics(String json) {
        assertThatThrownBy(() -> validation.candidate(json, validation.template(template, columns)))
            .isInstanceOf(SizeChartDraftTransportException.class).hasCause(null).hasMessageNotContaining("Alice").hasMessageNotContaining("private").hasMessageNotContaining("999");
    }

    private SizeChartDraftCreateCommand command(String source) {
        return new SizeChartDraftCreateCommand(10, 20, 40, UUID.fromString("11111111-1111-4111-8111-111111111111"), "TEXT", source);
    }
}
