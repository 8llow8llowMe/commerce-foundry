package com.commercefoundry.domainlayer.productdraftgeneration.application.info;

public record ProductDraftGenerationRegistrationInfo(long draftId, int generationNo, long jobId, String inputHash) {
}
