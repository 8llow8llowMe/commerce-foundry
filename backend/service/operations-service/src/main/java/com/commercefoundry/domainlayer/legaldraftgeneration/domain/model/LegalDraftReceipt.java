package com.commercefoundry.domainlayer.legaldraftgeneration.domain.model;

import java.time.Instant;

public record LegalDraftReceipt(String keyHash, long jobId, long memberId, LegalDraftInput input, Instant reviewedAt) {}
