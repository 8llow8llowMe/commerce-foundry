package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import java.time.Duration;

public interface SocialStateStorePort {
    boolean save(String stateHash, SocialProvider provider, String verifierHash, Duration ttl);
    boolean consume(String stateHash, SocialProvider provider, String verifierHash);
}