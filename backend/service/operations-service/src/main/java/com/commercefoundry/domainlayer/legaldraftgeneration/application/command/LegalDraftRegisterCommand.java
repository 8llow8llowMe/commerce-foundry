package com.commercefoundry.domainlayer.legaldraftgeneration.application.command;

import java.util.Map;

public record LegalDraftRegisterCommand(long memberId, String requestKey, String type, String locale, Integer templateVersion,
                                        Map<String, String> confirmedFacts) {
    @Override
    public String toString() {
        return "LegalDraftRegisterCommand[memberId=" + memberId + ", type=" + type + ", locale=" + locale + "]";
    }
}
