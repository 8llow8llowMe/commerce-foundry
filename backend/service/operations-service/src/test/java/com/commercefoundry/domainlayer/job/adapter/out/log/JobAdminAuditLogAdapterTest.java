package com.commercefoundry.domainlayer.job.adapter.out.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 감사 한 줄의 형식 — 로그 수집기가 이 모양으로 파싱한다 ({@code security-guide.md} §11-4). 칸 순서 · 이름을 바꾸면 이 테스트가 깨져야 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class JobAdminAuditLogAdapterTest {

    private final JobAdminAuditLogAdapter adapter = new JobAdminAuditLogAdapter();

    @Test
    @DisplayName("작업 취소 성공 · 실패는 action · actorMemberId · target · result 네 칸이다")
    void jobCancel(CapturedOutput output) {
        adapter.jobCancelSucceeded(9L, 42L);
        adapter.jobCancelFailed(9L, 43L);

        assertThat(output.getOut())
            .contains("admin.audit")
            .contains("action=JOB_CANCEL actorMemberId=9 target=job:42 result=SUCCESS")
            .contains("action=JOB_CANCEL actorMemberId=9 target=job:43 result=FAILURE");
    }
}
