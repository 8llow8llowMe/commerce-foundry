package com.commercefoundry.domainlayer.guestsession.application.service;

import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionIssuedInfo;
import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionResolvedInfo;
import com.commercefoundry.domainlayer.guestsession.application.port.in.GuestSessionInternalUseCase;
import com.commercefoundry.domainlayer.guestsession.application.service.processor.GuestSessionProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestSessionInternalFacade implements GuestSessionInternalUseCase {
    private final GuestSessionProcessor processor;

    // Suspend any caller transaction: Processor must commit before the credential leaves this facade.
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GuestSessionIssuedInfo issue() {
        return processor.issue();
    }

    @Override
    public GuestSessionResolvedInfo resolve(String token) {
        return processor.resolve(token);
    }

    @Override
    public void revoke(long sessionId) {
        processor.revoke(sessionId);
    }
}
