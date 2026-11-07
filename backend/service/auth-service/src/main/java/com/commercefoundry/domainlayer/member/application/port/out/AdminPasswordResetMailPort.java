package com.commercefoundry.domainlayer.member.application.port.out;

public interface AdminPasswordResetMailPort {
    void send(String recipient, String resetLink);
}
