package com.commercefoundry.domainlayer.job.application.model;

public record PreparedJobResultFile(long id, String eTag) {
    @Override
    public String toString() { return "PreparedJobResultFile[id=" + id + "]"; }
}
