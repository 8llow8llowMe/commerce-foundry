package com.commercefoundry.domainlayer.settlement.application.command;

import java.time.LocalDate;

/** @param soldDate 대사할 PG 매출일 (KST) */
public record SettlementReconcileCommand(LocalDate soldDate) {
}
