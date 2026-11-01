package com.commercefoundry.support;

import com.commercefoundry.domainlayer.member.application.port.out.AdminAccessCutoffPort;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 로그인 2단계 흐름에서 관리자 access 컷오프를 Redis 없이 확인한다. */
public class InMemoryAdminAccessCutoffStore implements AdminAccessCutoffPort {

    private final Map<Long, Instant> cutoffs = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextWrite = new AtomicBoolean();

    @Override
    public void cutOff(long memberId, Instant cutoff) {
        if (failNextWrite.getAndSet(false)) {
            throw new MemberException(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        }
        cutoffs.merge(memberId, cutoff, (previous, incoming) -> previous.isAfter(incoming) ? previous : incoming);
    }

    @Override
    public Optional<Instant> findCutoff(long memberId) {
        return Optional.ofNullable(cutoffs.get(memberId));
    }

    public void clear() {
        cutoffs.clear();
        failNextWrite.set(false);
    }

    public void failNextWrite() {
        failNextWrite.set(true);
    }
}
