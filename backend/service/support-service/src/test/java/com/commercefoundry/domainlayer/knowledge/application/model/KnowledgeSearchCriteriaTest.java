package com.commercefoundry.domainlayer.knowledge.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeSearchCommand;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeErrorCode;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeException;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KnowledgeSearchCriteriaTest {
    @Test
    void normalizesNfkcCasePunctuationAndEncounterOrderDeduplication() {
        assertThat(criteria("Shipping, SHIPPING! \uBC30\uC1A1 \uFF21\uFF22\uFF23 abc").tokens())
            .containsExactly("shipping", "\uBC30\uC1A1", "abc");
        assertThat(criteria("a-b.c_d%e!f 123").tokens()).containsExactly("a", "b", "c", "d", "e", "f", "123");
        assertThat(criteria("a".repeat(64)).tokens()).containsExactly("a".repeat(64));
    }

    @Test
    void usesOnlyFirstSixteenDistinctTokensAndIgnoresTheRest() {
        String first = IntStream.range(0, 16).mapToObj(i -> "t" + i).collect(Collectors.joining(" "));
        assertThat(criteria("t0 t0 " + first + " " + "x".repeat(65)).tokens())
            .containsExactlyElementsOf(IntStream.range(0, 16).mapToObj(i -> "t" + i).toList());
    }

    static Stream<Arguments> invalidCommands() {
        return Stream.of(
            Arguments.of((KnowledgeSearchCommand) null),
            Arguments.of(new KnowledgeSearchCommand(null, KnowledgeLocale.KO, null, 1)),
            Arguments.of(new KnowledgeSearchCommand(" \t\n", KnowledgeLocale.KO, null, 1)),
            Arguments.of(new KnowledgeSearchCommand("%_!", KnowledgeLocale.KO, null, 1)),
            Arguments.of(new KnowledgeSearchCommand("a".repeat(501), KnowledgeLocale.KO, null, 1)),
            Arguments.of(new KnowledgeSearchCommand("\uFDFA".repeat(30), KnowledgeLocale.KO, null, 1)),
            Arguments.of(new KnowledgeSearchCommand("a".repeat(65), KnowledgeLocale.KO, null, 1)),
            Arguments.of(new KnowledgeSearchCommand("valid", null, null, 1)),
            Arguments.of(new KnowledgeSearchCommand("valid", KnowledgeLocale.KO, null, 0)),
            Arguments.of(new KnowledgeSearchCommand("valid", KnowledgeLocale.KO, null, 21)));
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void rejectsInvalidInputWithExistingFixedError(KnowledgeSearchCommand command) {
        assertThatThrownBy(() -> KnowledgeSearchCriteria.from(command)).isInstanceOfSatisfying(KnowledgeException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(KnowledgeErrorCode.INVALID_INPUT));
    }

    @Test
    void rawAndNormalizedLengthBoundsAreIndependentAndSafeToStringOmitsQuestionAndTokens() {
        String atLimit = "a ".repeat(250);
        assertThat(criteria(atLimit).tokens()).containsExactly("a");
        var command = new KnowledgeSearchCommand("privateQuestion", KnowledgeLocale.KO, null, 20);
        assertThat(command.toString()).doesNotContain("privateQuestion");
        assertThat(KnowledgeSearchCriteria.from(command).toString()).doesNotContain("privatequestion");
    }

    private KnowledgeSearchCriteria criteria(String question) {
        return KnowledgeSearchCriteria.from(new KnowledgeSearchCommand(question, KnowledgeLocale.KO, null, 1));
    }
}
