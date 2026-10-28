package com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response;

import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.item.LookImageAdminItem;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.item.LookProductAdminItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 룩 한 개. 순서는 배열 순서로만 전하고 내부 순서 번호({@code displayOrder}) · 아이템 아이디는 내지 않는다 ({@code api-design-guide.md} §2-8).
 */
@Builder
@Schema(description = "어드민 룩 (숨긴 룩 포함)")
public record LookAdminResponse(

    @Schema(description = "룩 아이디", example = "4611686018427387904")
    String lookId,

    @Schema(description = "제목. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더)", example = "가을 나들이 룩")
    String title,

    @Schema(description = "설명. 없으면 null. 평문이다. HTML 로 해석하지 않는다(텍스트 노드로만 렌더, 개행은 white-space 로)", nullable = true)
    String description,

    @Schema(description = "대표 이미지")
    LookImageAdminItem image,

    @Schema(description = "고객 노출 여부 설정", example = "true")
    boolean visible,

    @Schema(description = "구성 상품 (배열 순서 = 구성 순서). 숨김 · 판매 종료 상품도 담는다")
    List<LookProductAdminItem> products
) {
}
