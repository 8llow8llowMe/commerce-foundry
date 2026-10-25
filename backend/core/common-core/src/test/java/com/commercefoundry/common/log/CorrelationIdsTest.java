package com.commercefoundry.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdsTest {

    @Test
    @DisplayName("UUID 하나는 소문자로 받고, 없거나 형식이 틀리면 새 id 를 만든다 — 틀린 원문은 결과에 남지 않는다")
    void acceptsOneUuidAndDropsAnythingElse() {
        assertThat(CorrelationIds.acceptOrCreate("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
            .isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        String forged = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\nINFO forged";
        String created = CorrelationIds.acceptOrCreate(forged);
        assertThat(created).isNotEqualTo(forged).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(created).doesNotContain("forged", "\n");
        assertThat(CorrelationIds.acceptOrCreate(null)).matches("[0-9a-f-]{36}");
        assertThat(CorrelationIds.acceptOrCreate("  ")).doesNotContain(" ");
    }

    @Test
    @DisplayName("MDC 에 검증된 id 만 current 로 읽힌다")
    void currentReadsOnlyAnAcceptableMdcValue() {
        MDC.put(CorrelationIds.MDC_KEY, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        try {
            assertThat(CorrelationIds.current()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            MDC.put(CorrelationIds.MDC_KEY, "not-a-uuid");
            assertThat(CorrelationIds.current()).isNull();
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
