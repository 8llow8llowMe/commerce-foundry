package com.commercefoundry.domainlayer.display.application.port.in;

import com.commercefoundry.domainlayer.display.adapter.in.web.dto.response.DisplayMainResponse;

public interface DisplayWebUseCase {

    /** 지금 보이는 섹션과 아이템 — 고객에게 보이지 않는 상품의 아이템은 빠진다. 실패하지 않는다(보이는 섹션이 없으면 빈 목록). */
    DisplayMainResponse getMain();
}
