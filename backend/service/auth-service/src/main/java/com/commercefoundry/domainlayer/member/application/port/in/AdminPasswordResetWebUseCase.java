package com.commercefoundry.domainlayer.member.application.port.in;

public interface AdminPasswordResetWebUseCase {
    void reset(String token, String newPassword, String clientSubject);
}
