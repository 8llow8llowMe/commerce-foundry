package com.commercefoundry.domainlayer.payment.domain.model;

/** Independent from the two #591 order-reconciliation lanes. */
public enum AutoCancelLane { REJECTED, LATE_APPROVED, PENDING }
