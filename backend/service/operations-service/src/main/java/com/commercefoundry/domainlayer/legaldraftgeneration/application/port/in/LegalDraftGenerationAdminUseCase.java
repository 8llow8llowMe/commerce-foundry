package com.commercefoundry.domainlayer.legaldraftgeneration.application.port.in;

import com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.response.LegalDraftGenerationResponse;
import com.commercefoundry.domainlayer.legaldraftgeneration.application.command.LegalDraftRegisterCommand;

public interface LegalDraftGenerationAdminUseCase {
    LegalDraftGenerationResponse.Accepted register(LegalDraftRegisterCommand command);

    LegalDraftGenerationResponse get(long memberId, long jobId);

    LegalDraftGenerationResponse review(long memberId, long jobId);

    LegalDraftGenerationResponse cancel(long memberId, long jobId);
}
