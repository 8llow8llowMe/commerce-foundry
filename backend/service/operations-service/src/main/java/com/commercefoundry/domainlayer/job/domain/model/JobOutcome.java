package com.commercefoundry.domainlayer.job.domain.model;

public record JobOutcome(String resultJson, JobResultContent file) {
    public JobOutcome(String resultJson) {
        this(resultJson, null);
    }

    public static JobOutcome empty() {
        return new JobOutcome(null);
    }

    @Override
    public String toString() {
        return "JobOutcome[file=" + (file != null) + "]";
    }
}
