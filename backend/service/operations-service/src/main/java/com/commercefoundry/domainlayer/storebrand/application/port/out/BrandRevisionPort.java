package com.commercefoundry.domainlayer.storebrand.application.port.out;

import com.commercefoundry.domainlayer.storebrand.domain.model.BrandRevision;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.Optional;

public interface BrandRevisionPort {
    Optional<BrandRevision> latest();
    Optional<BrandRevision> find(long version);
    void append(BrandRevision revision);
    PageResponse<BrandRevision> publications(int page, int size);
}
