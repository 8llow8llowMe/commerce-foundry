package com.commercefoundry.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.job.adapter.in.scheduler.JobResultCleanupScheduler;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;

/** 환경변수 직접 바인딩과 @Scheduled placeholder의 이름 해석 차이를 실제 yml로 검증한다. */
class JobResultCleanupEnvironmentTest {
    @Test
    void documentedEnvironmentKeysConfigureScheduler() throws Exception {
        var environment = environment(Map.of(
            "OPERATIONS_JOBS_RESULTFILE_CLEANUPINITIALDELAY", "45s",
            "OPERATIONS_JOBS_RESULTFILE_CLEANUPDELAY", "2m"));
        Scheduled scheduled = JobResultCleanupScheduler.class.getMethod("cleanup").getAnnotation(Scheduled.class);
        assertThat(environment.resolveRequiredPlaceholders(scheduled.initialDelayString())).isEqualTo("45s");
        assertThat(environment.resolveRequiredPlaceholders(scheduled.fixedDelayString())).isEqualTo("2m");
    }

    @Test
    void unsetSchedulerEnvironmentKeysUseOneMinuteDefaults() throws Exception {
        var environment = environment(Map.of());
        Scheduled scheduled = JobResultCleanupScheduler.class.getMethod("cleanup").getAnnotation(Scheduled.class);
        assertThat(environment.resolveRequiredPlaceholders(scheduled.initialDelayString())).isEqualTo("1m");
        assertThat(environment.resolveRequiredPlaceholders(scheduled.fixedDelayString())).isEqualTo("1m");
    }

    private static MockEnvironment environment(Map<String, Object> values) throws IOException {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("result-file-env", values));
        for (var source : new YamlPropertySourceLoader().load("operations-yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
