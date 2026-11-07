package com.commercefoundry.domainlayer.sizechart.application.model;

/** Stable recovery ID for the admin adapter's 503 Location header. Never retain transport diagnostics. */
public class SizeChartDraftUnavailableException extends SizeChartDraftTransportException {
    private final long draftId;

    public SizeChartDraftUnavailableException(long draftId, SizeChartDraftTransportErrorCode errorCode) {
        super(errorCode);
        this.draftId = draftId;
    }

    public long getDraftId() {
        return draftId;
    }
}
