package com.commercefoundry.domainlayer.shippingfee.application.service;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.InternationalShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter.InternationalShippingPolicyAdminPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.InternationalShippingPolicyAdminUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.InternationalShippingPolicyProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InternationalShippingPolicyAdminFacade implements InternationalShippingPolicyAdminUseCase {

    private final InternationalShippingPolicyProcessor internationalShippingPolicyProcessor;
    private final InternationalShippingPolicyAdminPresenter internationalShippingPolicyAdminPresenter;

    /**
     * 네 테이블(정책 행 · 그룹 · 국가 · 구간)을 같은 스냅숏에서 읽는다. 교체가 하위 행 아이디를 새로 발급하므로 READ COMMITTED 로 문장마다
     * 스냅숏이 다르면 그룹이 비어 {@code ShippingZone} 이 {@link IllegalArgumentException}(500)을 낸다 — 국내 조회(정책 행 + 구간)와 판단이 다른 이유다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InternationalShippingPolicyAdminResponse getInternationalPolicy() {
        return internationalShippingPolicyProcessor.findPolicy().map(internationalShippingPolicyAdminPresenter::toResponse).orElse(null);
    }

    /**
     * 정책 행 갱신과 국가 그룹 · 국가 · 무게 구간 삭제 · 삽입을 한 트랜잭션으로 묶는다 — 실패(버전 충돌 포함)하면 아무것도 바뀌지 않는다. 외부 I/O 가 없다.
     * 기본 격리 수준(READ COMMITTED)이다 — 교체 전 조회가 동시 교체와 겹쳐 하위 행이 엇갈리면 어댑터가 버전 충돌로 알려 006 이 된다.
     */
    @Override
    @Transactional
    public InternationalShippingPolicyAdminResponse replaceInternationalPolicy(InternationalShippingPolicyReplaceCommand command) {
        return internationalShippingPolicyAdminPresenter.toResponse(internationalShippingPolicyProcessor.replacePolicy(command));
    }
}
