package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import java.time.Duration;
import java.util.Optional;

public interface AdminPasswordResetTokenPort {
    void save(String fingerprint, AdminPasswordResetGrant grant, Duration ttl);
    Optional<AdminPasswordResetGrant> consume(String fingerprint);
    void discard(String fingerprint);
    /** 계정 존재 여부와 무관하게 고정 창을 센다. 저장소 장애는 fail-closed다. */
    void limit(String dimension, int maximum, Duration window);
}
