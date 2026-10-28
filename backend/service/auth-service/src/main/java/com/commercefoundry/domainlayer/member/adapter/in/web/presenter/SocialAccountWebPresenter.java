package com.commercefoundry.domainlayer.member.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.web.dto.response.SocialAccountsResponse;
import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SocialAccountWebPresenter {
    public SocialAccountsResponse toResponse(List<SocialProvider> providers) {
        return new SocialAccountsResponse(providers.stream().map(provider -> provider.name().toLowerCase(Locale.ROOT)).toList());
    }
}
