package com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.common.dto.item.MoneyItem;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductOptionGroupAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductSkuAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductImageAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.CustomOrderFieldAdminItem;

@Builder
@Schema(description = "어드민 상품 상세 응답")
public record ProductDetailAdminResponse(

    @Schema(description = "상품 아이디", example = "4611686018427387904")
    String productId,

    @Schema(description = "대표 카테고리 아이디", example = "4611686018427387904")
    String categoryId,

    @Schema(description = "대표와 진열 카테고리 아이디의 합집합(오름차순)")
    List<String> categoryIds,

    @Schema(description = "상품명")
    String name,

    @Schema(description = "요약", nullable = true)
    String summary,

    @Schema(description = "정제하지 않은 상세 설명 HTML. FE에서 sanitize 후 렌더링", nullable = true)
    String descriptionHtml,

    @Schema(description = "저장된 판매 상태")
    CodeNameDescriptionMetadata saleStatus,

    @Schema(description = "노출 여부")
    boolean visible,

    @Schema(description = "저장된 기본 판매가")
    MoneyItem salePrice,

    @Schema(description = "저장된 정가. 미입력이면 null", nullable = true)
    MoneyItem listPrice,

    @Schema(description = "1회 주문 최대 수량. 제한 없으면 null", nullable = true)
    Integer maxQuantityPerOrder,

    @Schema(description = "맞춤 제작 여부")
    boolean customOrder,

    @Schema(description = "맞춤 제작 안내", nullable = true)
    String customOrderNotice,

    @Schema(description = "맞춤 제작 소요 일수", nullable = true)
    Integer customOrderLeadTimeDays,

    @Schema(description = "프리오더 여부")
    boolean preorder,

    @Schema(description = "발송 예정일. 미입력이면 null", nullable = true)
    LocalDate expectedShipDate,

    @Schema(description = "상품 낙관적 락 버전", nullable = true)
    Long version,

    @Schema(description = "등록자 회원 아이디. 이관/시드이면 null", nullable = true)
    String createdByMemberId,

    @Schema(description = "은퇴 그룹과 값을 포함한 옵션 그룹")
    List<ProductOptionGroupAdminItem> optionGroups,

    @Schema(description = "판매 종료를 포함한 SKU 목록(아이디 오름차순)")
    List<ProductSkuAdminItem> skus,

    @Schema(description = "대표/갤러리/영상 목록(진열 순서, 아이디 오름차순)")
    List<ProductImageAdminItem> images,

    @Schema(description = "저장된 맞춤 제작 입력 항목")
    List<CustomOrderFieldAdminItem> customOrderFields
) {
}
