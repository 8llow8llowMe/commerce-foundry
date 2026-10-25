package com.commercefoundry.domainlayer.member.application.service.processor;

import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditLogRetentionRepositoryPort;
import com.commercefoundry.global.properties.AdminAuditRetentionProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 보관 컷오프와 배치 삭제 (#286, {@code security-guide.md} §11-4). 트랜잭션은 부른 Facade 가 건다 — 배치는 호출 한 번이다.
 *
 * <p>지울 범위는 {@code created_at} 이 아니라 Snowflake 아이디다. 아이디의 시각 칸이 행위 시각이라 {@code id < 컷오프} 가
 * 그 밀리초보다 이전 행만 고르고, 컷오프 시각의 행은 남는다.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLogRetentionProcessor {

    private final AdminAuditLogRetentionRepositoryPort adminAuditLogRetentionPort;
    private final AdminAuditRetentionProperties adminAuditRetentionProperties;
    private final Clock clock;

    public long cutoffId() {
        Instant cutoff = clock.instant().atZone(ZoneOffset.UTC).minus(adminAuditRetentionProperties.period()).toInstant();
        return SnowflakeIdGenerator.lowestIdAt(cutoff);
    }

    /** 고른 건수를 돌려준다. 같은 행을 다른 인스턴스가 먼저 지웠어도 다음 배치는 남은 아이디부터 읽는다. */
    public int deleteBatch(long cutoffId, int batchSize) {
        List<Long> ids = adminAuditLogRetentionPort.findIdsOlderThan(cutoffId, batchSize);
        if (ids.isEmpty()) {
            return 0;
        }
        adminAuditLogRetentionPort.deleteByIds(ids);
        return ids.size();
    }
}
