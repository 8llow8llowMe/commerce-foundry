package com.commercefoundry.support;

import com.commercefoundry.domainlayer.member.application.port.out.AdminAccessCutoffPort;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 로그인 2단계 흐름에서 관리자 access 컷오프를 Redis 없이 확인한다. */
public class InMemoryAdminAccessCutoffStore implements AdminAccessCutoffPort {

    private final Map<Long, Instant> cutoffs = new ConcurrentHashMap<>();

    @Override
    public void cutOff(long memberId, Instant cutoff) {
        cutoffs.put(memberId, cutoff);
    }

    @Override
    public Optional<Instant> findCutoff(long memberId) {
        return Optional.ofNullable(cutoffs.get(memberId));
    }

    public void clear() {
        cutoffs.clear();
    }
}
