package com.commercefoundry.domainlayer.guestsession.application.port.in;

import com.commercefoundry.domainlayer.guestsession.adapter.in.web.dto.response.GuestSessionWebResponses;

public interface GuestSessionWebUseCase {
    GuestSessionWebResponses.Issued issue();
}
