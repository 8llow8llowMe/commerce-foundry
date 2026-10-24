package com.commercefoundry.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.ai.client.AiRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 픽스처 값은 가짜다. */
class PromptTemplateTest {

    private final PromptTemplate template = new PromptTemplate("cs-reply", 3, "답변 도우미다. 상품은 {{productName}} 이다.",
        "고객 {{pii:customerName}} 님({{pii:orderNumber}})의 문의:\n{{inquiry}}");

    @Test
    @DisplayName("값을 채워 요청을 만든다 — 개인정보 자리 값은 sensitiveValues 로 넘어가고 버전이 실린다")
    void renders() {
        AiRequest request = template.render(PromptValues.create()
            .put("productName", "철릭 원피스").put("inquiry", "사이즈 문의입니다")
            .putSensitive("customerName", "Hong Gildong").putSensitive("orderNumber", "CF-20261024-0001"));

        assertThat(request.feature()).isEqualTo("cs-reply");
        assertThat(request.promptVersion()).isEqualTo(3);
        assertThat(request.system()).isEqualTo("답변 도우미다. 상품은 철릭 원피스 이다.");
        assertThat(request.prompt()).isEqualTo("고객 Hong Gildong 님(CF-20261024-0001)의 문의:\n사이즈 문의입니다");
        assertThat(request.sensitiveValues()).containsExactlyInAnyOrder("Hong Gildong", "CF-20261024-0001");
    }

    @Test
    @DisplayName("개인정보 자리에 일반 값을 넣거나 반대로 넣으면 실패한다 — 개인정보가 마스킹 목록 밖으로 새지 않게. 메시지에 값이 없다")
    void rejectsWrongKind() {
        assertThatThrownBy(() -> template.render(PromptValues.create()
                .put("productName", "p").put("inquiry", "q").put("customerName", "Hong Gildong").putSensitive("orderNumber", "CF-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pii:customerName").hasMessageContaining("putSensitive").message().doesNotContain("Hong Gildong");
    }

    @Test
    @DisplayName("자리에 값이 없거나, 없는 자리에 값을 넘기면 실패한다")
    void rejectsMissingAndUnused() {
        assertThatThrownBy(() -> template.render(PromptValues.create().put("productName", "p").putSensitive("customerName", "c").putSensitive("orderNumber", "o")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'inquiry'");
        assertThatThrownBy(() -> template.render(PromptValues.create().put("productName", "p").put("inquiry", "q").put("typo", "x")
                .putSensitive("customerName", "c").putSensitive("orderNumber", "o")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("typo");
    }

    @Test
    @DisplayName("값 안의 {{...}} 는 다시 바뀌지 않는다 — 한 번만 훑는다")
    void doesNotReexpandValues() {
        AiRequest request = template.render(PromptValues.create()
            .put("productName", "{{inquiry}}").put("inquiry", "{{pii:customerName}}")
            .putSensitive("customerName", "Hong Gildong").putSensitive("orderNumber", "CF-1"));

        assertThat(request.system()).contains("{{inquiry}}");
        assertThat(request.prompt()).endsWith("{{pii:customerName}}");
    }

    @Test
    @DisplayName("규칙에 맞지 않는 자리 표기(오타)는 만들 때 막는다")
    void rejectsBrokenSlot() {
        assertThatThrownBy(() -> new PromptTemplate("cs-reply", 1, null, "고객 {{ customerName }} 님"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cs-reply v1");
        assertThatThrownBy(() -> new PromptTemplate("cs-reply", 1, null, "고객 {{pii-name}} 님")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("자리 목록을 돌려주고, toString 과 값 묶음의 toString 에 문구 · 값이 없다")
    void slotsAndToString() {
        PromptValues values = PromptValues.create().putSensitive("customerName", "Hong Gildong");

        assertThat(template.slots()).containsExactly("productName", "pii:customerName", "pii:orderNumber", "inquiry");
        assertThat(template.toString()).doesNotContain("답변 도우미").doesNotContain("문의:");
        assertThat(values.toString()).doesNotContain("Hong Gildong").contains("customerName");
    }
}
