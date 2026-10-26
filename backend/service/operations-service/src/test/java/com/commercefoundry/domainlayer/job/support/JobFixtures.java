package com.commercefoundry.domainlayer.job.support;

import com.commercefoundry.domainlayer.job.domain.model.JobExecution;
import com.commercefoundry.domainlayer.job.domain.model.JobHandler;
import com.commercefoundry.domainlayer.job.domain.model.JobHandlerOptions;
import com.commercefoundry.domainlayer.job.domain.model.JobOutcome;
import com.commercefoundry.global.properties.JobProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 작업 엔진 단위 테스트의 공용 값 — 설정 기본값(application.yml 과 같다) · 핸들러 · 빈 목록. */
public final class JobFixtures {

    private JobFixtures() {
    }

    /** application.yml 기본값. */
    public static JobProperties properties() {
        return new JobProperties(
            new JobProperties.Worker(
                true, 2, Duration.ofSeconds(2), Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofSeconds(30)
            ),
            new JobProperties.LeaseSweep(Duration.ofSeconds(30), Duration.ofSeconds(30), 100, 10),
            new JobProperties.Retry(3, Duration.ofSeconds(30), 4, Duration.ofMinutes(30), 0.2),
            new JobProperties.Result(65536),
            new JobProperties.Planner(true, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofDays(32), List.of())
        );
    }

    /** 아무것도 하지 않고 성공하는 핸들러. */
    public static JobHandler handler(String type, JobHandlerOptions options) {
        return new JobHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public JobHandlerOptions options() {
                return options;
            }

            @Override
            public JobOutcome execute(JobExecution execution) {
                return JobOutcome.empty();
            }
        };
    }

    public static JobHandler handler(String type) {
        return handler(type, JobHandlerOptions.defaults());
    }

    /** 스프링이 주입하는 핸들러 빈 목록 — 등록 순서대로다. */
    public static ObjectProvider<JobHandler> handlers(JobHandler... handlers) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        for (int index = 0; index < handlers.length; index++) {
            beanFactory.addBean("handler" + index, handlers[index]);
        }
        return beanFactory.getBeanProvider(JobHandler.class);
    }
}
