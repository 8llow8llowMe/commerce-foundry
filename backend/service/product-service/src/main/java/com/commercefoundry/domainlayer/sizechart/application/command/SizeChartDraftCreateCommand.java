package com.commercefoundry.domainlayer.sizechart.application.command;

import java.util.UUID;

public record SizeChartDraftCreateCommand(long productId, long templateId, long actorId, UUID idempotencyKey, String mode, String sourceText) {
    @Override
    public String toString() {
        return "SizeChartDraftCreateCommand[redacted]";
    }
}
