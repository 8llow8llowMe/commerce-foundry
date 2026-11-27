package com.commercefoundry.domainlayer.legaldraftgeneration.application.port.out;

import com.commercefoundry.domainlayer.legaldraftgeneration.domain.model.LegalDraftPlan;
import java.util.List;

public interface LegalDraftAiPort {
    LegalDraftPlan generate(String type, String locale, int templateVersion, List<String> sectionIds, List<String> confirmedSectionIds,
                            long jobId, int attempt);
}
