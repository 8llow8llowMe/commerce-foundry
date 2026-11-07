package com.commercefoundry.domainlayer.member.application.port.in;

public interface AdminPasswordResetAdminUseCase {
    void send(long actorMemberId, long targetMemberId);
}
