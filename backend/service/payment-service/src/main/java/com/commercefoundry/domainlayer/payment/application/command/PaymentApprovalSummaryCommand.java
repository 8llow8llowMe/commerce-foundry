package com.commercefoundry.domainlayer.payment.application.command;

import java.time.Instant;

public record PaymentApprovalSummaryCommand(Instant from, Instant to) {
}
