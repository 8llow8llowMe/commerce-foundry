package com.commercefoundry.domainlayer.legaldraftgeneration.application.port.in;

import com.commercefoundry.domainlayer.legaldraftgeneration.application.info.LegalDraftInfo;
import java.util.Map;

public interface LegalDraftGenerationUseCase {
    long register(long memberId, String requestKey, String type, String locale, int templateVersion, Map<String, String> facts);
    LegalDraftInfo get(long memberId, long jobId);
    LegalDraftInfo review(long memberId, long jobId);
    LegalDraftInfo cancel(long memberId, long jobId);
}
