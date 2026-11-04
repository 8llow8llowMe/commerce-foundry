package com.commercefoundry.domainlayer.legaldocument.application.command;

public final class LegalDocumentCommands {
    private LegalDocumentCommands() {
    }

    public record SaveDraft(String type, String locale, Long expectedVersion, String title, String body) {
    }

    public record Publish(String type, String locale, Long expectedVersion, int revisionNumber, boolean reviewConfirmed) {
    }
}
