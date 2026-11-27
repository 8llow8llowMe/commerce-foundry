package com.commercefoundry.domainlayer.storebrand.application.port.out;

import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets.Kind;
import java.util.UUID;

public interface BrandImagePort {
    void put(Kind kind, UUID id, byte[] png);
    boolean exists(Kind kind, UUID id);
    byte[] read(Kind kind, UUID id);
}
