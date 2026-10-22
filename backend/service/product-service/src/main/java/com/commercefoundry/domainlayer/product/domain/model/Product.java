package com.commercefoundry.domainlayer.product.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 상품. 가격 계산(SKU 판매가 · 할인율)은 {@link ProductPricing} 한 곳에서만 한다.
 *
 * <p>프리오더(예약 판매)는 표시 정보다 — 판매 가능 판정 · 재고 차감은 일반 상품과 같다 ({@link SaleAvailability}).
 *
 * @param categoryId 대표 카테고리 아이디 (상세 경로 표시용). 진열 카테고리 목록에도 반드시 들어 있다
 * @param descriptionHtml 상세 설명 HTML. 표시 전 sanitize 대상
 * @param salePrice 기본 판매가. SKU 추가금의 기준이자 목록 최저가
 * @param listPrice 정가 (할인 전). null 이면 할인 표시 없음
 * @param maxQuantityPerOrder 1회 주문 최대 수량. null 이면 제한 없음
 * @param preorder 프리오더(예약 판매) 상품 여부
 * @param expectedShipDate 발송 예정일 ("○월 ○일 순차 발송"). 프리오더가 아니면 반드시 null, 프리오더인데 null 이면 "발송일 추후 안내" 다
 * @param version 낙관적 락 버전. 아직 저장하지 않은 상품은 null 이다
 * @param deletedAt 삭제(soft delete) 시각. null 이면 삭제되지 않았다. 저장된 삭제 상품은 숨김 · 판매 종료다(DB check) —
 *     판매 가능 판정은 그 check 에 기대지 않고 이 값도 본다 ({@link SaleAvailability#isOpenForSale})
 * @param createdByMemberId 등록한 어드민 회원 아이디 (auth-service member.id). 이관 · 시드 상품은 null. 수정 · 삭제로 바뀌지 않는다
 */
public record Product(
    long id, long categoryId, String name, String summary, String descriptionHtml,
    SaleStatus saleStatus, boolean visible, Money salePrice, Money listPrice, Integer maxQuantityPerOrder,
    boolean customOrder, String customOrderNotice, Integer customOrderLeadTimeDays,
    boolean preorder, LocalDate expectedShipDate, Long version, Instant deletedAt, Long createdByMemberId
) {

    public Product {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(saleStatus, "saleStatus");
        Objects.requireNonNull(salePrice, "salePrice");
        // DB check ck_product_expected_ship_date_preorder 와 같은 규칙이다
        if (!preorder && expectedShipDate != null) {
            throw new IllegalArgumentException("expectedShipDate requires preorder. productId=" + id);
        }
    }

    /** 삭제(soft delete)된 상품인가. */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
