package com.commercefoundry.domainlayer.payment.application.port.out.query;

/** 취소 결과가 불명확하면 성공 또는 실패로 확정하지 않는다. */
public sealed interface PgCancelResult {
    record Succeeded() implements PgCancelResult { }
    record Rejected() implements PgCancelResult { }
    record Unknown() implements PgCancelResult { }
}
