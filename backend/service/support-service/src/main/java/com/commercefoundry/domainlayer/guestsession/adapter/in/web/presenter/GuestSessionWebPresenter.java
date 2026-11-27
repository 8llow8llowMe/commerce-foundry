package com.commercefoundry.domainlayer.guestsession.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.guestsession.adapter.in.web.dto.response.GuestSessionWebResponses;
import com.commercefoundry.domainlayer.guestsession.application.info.GuestSessionIssuedInfo;
import org.springframework.stereotype.Component;

@Component
public class GuestSessionWebPresenter {
    public GuestSessionWebResponses.Issued issued(GuestSessionIssuedInfo info) {
        return new GuestSessionWebResponses.Issued(info.token(), info.expiresAt());
    }
}
