package com.commercefoundry.domainlayer.member.adapter.in.internal.presenter;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.AdminNotificationRecipientInternalResponse;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AdminNotificationRecipientInternalPresenter {

    public AdminNotificationRecipientInternalResponse toResponse(List<Long> memberIds) {
        return new AdminNotificationRecipientInternalResponse(memberIds.stream().map(String::valueOf).toList());
    }
}
