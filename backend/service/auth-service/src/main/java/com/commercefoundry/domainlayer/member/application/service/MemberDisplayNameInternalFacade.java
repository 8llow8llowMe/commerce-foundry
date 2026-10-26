package com.commercefoundry.domainlayer.member.application.service;

import com.commercefoundry.domainlayer.member.adapter.in.internal.dto.response.MemberDisplayNameInternalResponse;
import com.commercefoundry.domainlayer.member.adapter.in.internal.presenter.MemberDisplayNameInternalPresenter;
import com.commercefoundry.domainlayer.member.application.port.in.MemberDisplayNameInternalUseCase;
import com.commercefoundry.domainlayer.member.application.service.processor.MemberNameQueryProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberDisplayNameInternalFacade implements MemberDisplayNameInternalUseCase {

    private final MemberNameQueryProcessor memberNameQueryProcessor;
    private final MemberDisplayNameInternalPresenter memberDisplayNameInternalPresenter;

    @Override
    @Transactional(readOnly = true)
    public MemberDisplayNameInternalResponse getDisplayNames(List<Long> memberIds) {
        return memberDisplayNameInternalPresenter.toResponse(memberIds, memberNameQueryProcessor.findNamesByIds(memberIds));
    }
}
