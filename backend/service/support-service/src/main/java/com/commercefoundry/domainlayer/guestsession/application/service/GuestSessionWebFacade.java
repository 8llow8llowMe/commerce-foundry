package com.commercefoundry.domainlayer.guestsession.application.service;

import com.commercefoundry.domainlayer.guestsession.adapter.in.web.dto.response.GuestSessionWebResponses;
import com.commercefoundry.domainlayer.guestsession.adapter.in.web.presenter.GuestSessionWebPresenter;
import com.commercefoundry.domainlayer.guestsession.application.port.in.GuestSessionWebUseCase;
import com.commercefoundry.domainlayer.guestsession.application.service.processor.GuestSessionProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuestSessionWebFacade implements GuestSessionWebUseCase {
    private final GuestSessionProcessor processor;
    private final GuestSessionWebPresenter presenter;

    // No facade transaction: Processor commits its own transaction before the credential reaches the response.
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public GuestSessionWebResponses.Issued issue() {
        return presenter.issued(processor.issue());
    }
}
