package com.commercefoundry.domainlayer.job.application.service.processor;

import static com.commercefoundry.domainlayer.job.support.JobFixtures.handler;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.handlers;
import static com.commercefoundry.domainlayer.job.support.JobFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobHandlerProcessorTest {

    @Test
    @DisplayName("종류로 핸들러를 찾고, 비운 옵션 칸은 설정 기본값으로 채운다")
    void resolvesHandlersWithDefaults() {
        JobHandlerProcessor processor = new JobHandlerProcessor(
            handlers(handler("REPORT_EXPORT"), handler("AI_DRAFT", new JobHandlerOptions(Duration.ofMinutes(10), 1, null, 1))),
            properties()
        );

        assertThat(processor.types()).containsExactly("REPORT_EXPORT", "AI_DRAFT");
        assertThat(processor.find("REPORT_EXPORT").orElseThrow().options())
            .isEqualTo(new JobHandlerOptions(Duration.ofMinutes(5), 3, Duration.ofMinutes(30), 2));
        assertThat(processor.find("AI_DRAFT").orElseThrow().options())
            .isEqualTo(new JobHandlerOptions(Duration.ofMinutes(10), 1, Duration.ofMinutes(30), 1));
        assertThat(processor.find("UNKNOWN")).isEmpty();
    }

    @Test
    @DisplayName("핸들러가 없어도 뜬다 — 종류가 비어 워커가 조회하지 않는다")
    void noHandlers() {
        assertThat(new JobHandlerProcessor(handlers(), properties()).types()).isEmpty();
    }

    @Test
    @DisplayName("같은 종류를 맡는 핸들러가 둘이면 기동하지 않는다")
    void rejectsDuplicateType() {
        assertThatThrownBy(() -> new JobHandlerProcessor(handlers(handler("REPORT_EXPORT"), handler("REPORT_EXPORT")), properties()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REPORT_EXPORT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"report", "REPORT-EXPORT", ""})
    @DisplayName("종류 형식이 [A-Z0-9_]{1,64} 가 아니면 기동하지 않는다")
    void rejectsInvalidType(String type) {
        assertThatThrownBy(() -> new JobHandlerProcessor(handlers(handler(type)), properties())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("핸들러 lease 가 heartbeat 주기(1m)의 3배보다 짧으면 기동하지 않는다")
    void rejectsShortLease() {
        JobHandlerOptions shortLease = new JobHandlerOptions(Duration.ofMinutes(2), null, null, null);

        assertThatThrownBy(() -> new JobHandlerProcessor(handlers(handler("REPORT_EXPORT", shortLease)), properties()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("종류별 동시 상한이 인스턴스 전체 상한(2)을 넘으면 기동하지 않는다")
    void rejectsConcurrencyAboveInstanceLimit() {
        JobHandlerOptions tooMany = new JobHandlerOptions(null, null, null, 3);

        assertThatThrownBy(() -> new JobHandlerProcessor(handlers(handler("REPORT_EXPORT", tooMany)), properties()))
            .isInstanceOf(IllegalStateException.class);
    }
}
