package com.commercefoundry.domainlayer.display.adapter.in.web.dto.response;

import com.commercefoundry.domainlayer.display.adapter.in.web.dto.item.DisplaySectionItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
@Schema(description = "메인 전시 — 지금 보이는 섹션을 노출 순서대로 담는다")
public record DisplayMainResponse(

    @Schema(description = "섹션 목록 (노출 순서 → 아이디 오름차순). 보이는 섹션이 없으면 빈 배열")
    List<DisplaySectionItem> sections
) {
}
