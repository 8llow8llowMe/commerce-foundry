package com.commercefoundry.domainlayer.member.application.command;

import com.commercefoundry.domainlayer.member.domain.model.SignUpAgreements;

public record SocialLoginCommand(String code, String state, String verifier, SignUpAgreements agreements) {
    @Override
    public String toString() {
        return "SocialLoginCommand[redacted]";
    }
}