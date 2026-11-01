package com.commercefoundry.domainlayer.aiusage.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.commercefoundry.domainlayer.aiusage.application.port.in.AiUsageMaintenanceUseCase;
import java.time.Duration;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 원장 정리 스케줄러 — UseCase 를 부르고, 실패는 삼켜 다음 주기로 넘기며 같은 원인은 간격당 한 번만 WARN(타입만)이다. */
@ExtendWith(OutputCaptureExtension.class)
class AiUsageMaintenanceSchedulerTest {

    private static final String LEAK_PROBE = "SweepLeakProbe-91c2";

    private final FakeMaintenance maintenance = new FakeMaintenance();
    private final AiUsageMaintenanceScheduler scheduler = new AiUsageMaintenanceScheduler(maintenance, Duration.ofMinutes(1), Duration.ofHours(1));

    @Test
    @DisplayName("회수가 닫은 건이 있으면 WARN 으로 건수를 남긴다 — 정산이 오지 않은 호출의 신호다")
    void abandonStaleLogsCount(CapturedOutput output) {
        maintenance.abandon = () -> 3;

        scheduler.abandonStale();

        assertThat(maintenance.abandonCalls).isEqualTo(1);
        assertThat(output).contains("ai usage stale reservations abandoned. count=3");
    }

    @Test
    @DisplayName("닫을 것 · 지울 것이 없으면 아무것도 남기지 않는다")
    void nothingToDoIsQuiet(CapturedOutput output) {
        scheduler.abandonStale();
        scheduler.deleteExpired();

        assertThat(maintenance.abandonCalls).isEqualTo(1);
        assertThat(maintenance.deleteCalls).isEqualTo(1);
        assertThat(output).doesNotContain("ai usage");
    }

    @Test
    @DisplayName("보존 정리가 지운 건수를 INFO 로 남긴다")
    void deleteExpiredLogsCount(CapturedOutput output) {
        maintenance.delete = () -> 42;

        scheduler.deleteExpired();

        assertThat(output).contains("ai usage expired calls deleted. count=42");
    }

    @Test
    @DisplayName("실패는 던지지 않고, 같은 원인은 간격 안에서 한 번만 타입으로 WARN 한다 — 예외 메시지는 남기지 않는다")
    void failuresAreSwallowedAndThrottled(CapturedOutput output) {
        maintenance.abandon = () -> {
            throw new IllegalStateException(LEAK_PROBE);
        };
        maintenance.delete = () -> {
            throw new IllegalStateException(LEAK_PROBE);
        };

        assertThatCode(() -> {
            scheduler.abandonStale();
            scheduler.abandonStale();
            scheduler.deleteExpired();
        }).doesNotThrowAnyException();

        assertThat(maintenance.abandonCalls).isEqualTo(2);
        assertThat(output.getOut().split("ai usage reservation sweep failed", -1)).hasSize(2);
        assertThat(output).contains("ai usage retention cleanup failed").contains(IllegalStateException.class.getName()).doesNotContain(LEAK_PROBE);
    }

    private static final class FakeMaintenance implements AiUsageMaintenanceUseCase {

        private IntSupplier abandon = () -> 0;
        private IntSupplier delete = () -> 0;
        private int abandonCalls;
        private int deleteCalls;

        @Override
        public int abandonStale() {
            abandonCalls++;
            return abandon.getAsInt();
        }

        @Override
        public int deleteExpired() {
            deleteCalls++;
            return delete.getAsInt();
        }
    }
}
