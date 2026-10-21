package com.commercefoundry.domainlayer.shippingfee.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 국내 배송비 정책 — 기본 배송비, 무료배송 기준, 도서산간 구간. 해외는 {@link InternationalShippingPolicy} 다 (같은 {@code shipping_policy} 테이블의 다른 범위 행).
 *
 * <p>만들 때 불변식을 지킨다 — 범위는 국내, 모든 금액이 같은 통화, 무료배송 기준은 없거나 0 보다 크다, 도서산간 구간은 서로 겹치지 않는다.
 * 어긋나면 {@link IllegalArgumentException} 이다. 어드민 입력은 Processor 가 먼저 도메인 오류 코드로 거절하므로 여기까지 오는 위반은 코드 · 데이터 오류다.
 *
 * @param freeShippingThreshold 무료배송 기준 주문 금액. null 이면 무료배송이 없다
 * @param remoteAreas 시작 우편번호 오름차순으로 정렬해 들고 있는다
 * @param version 낙관적 락 버전. 아직 저장되지 않은 정책이면 null — 저장된 정책을 교체할 때는 호출자가 본 버전이다
 * @param updatedAt 마지막 저장 시각. 아직 저장되지 않은 정책이면 null
 */
public record ShippingPolicy(
    long id, ShippingScope scope, Money baseFee, Money freeShippingThreshold,
    List<RemoteArea> remoteAreas, Long version, Instant updatedAt
) {

    private static final Comparator<RemoteArea> BY_POSTAL_CODE_FROM = Comparator.comparing(RemoteArea::postalCodeFrom);

    public ShippingPolicy {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(baseFee, "baseFee");
        Objects.requireNonNull(remoteAreas, "remoteAreas");
        if (scope != ShippingScope.DOMESTIC) {
            // 해외 행을 국내 모델로 읽으면 국가 그룹 · 무게 구간을 잃은 채 계산한다 — 매퍼 · 포트를 잘못 쓴 코드 오류다
            throw new IllegalArgumentException("국내 배송비 정책 모델은 DOMESTIC 범위만 담습니다: " + scope);
        }
        CurrencyCode currency = baseFee.currency();
        if (freeShippingThreshold != null) {
            requireCurrency(currency, freeShippingThreshold, "freeShippingThreshold");
            if (freeShippingThreshold.isZero()) {
                throw new IllegalArgumentException("무료배송 기준 금액은 0 보다 커야 합니다");
            }
        }
        remoteAreas.forEach(area -> requireCurrency(currency, area.surcharge(), "remoteArea " + area.name()));
        remoteAreas = remoteAreas.stream().sorted(BY_POSTAL_CODE_FROM).toList();
        findFirstOverlap(remoteAreas).ifPresent(overlap -> {
            throw new IllegalArgumentException("도서산간 구간이 겹칩니다: %s, %s".formatted(overlap.first().name(), overlap.second().name()));
        });
    }

    /**
     * 처음 만나는 겹친 두 구간. 시작 우편번호 순으로 놓으면 겹친 쌍이 있을 때 반드시 이웃한 쌍 중에 있다.
     *
     * @return 시작 우편번호 순으로 앞 · 뒤 구간. 겹치지 않으면 빈 값
     */
    public static Optional<Overlap> findFirstOverlap(List<RemoteArea> remoteAreas) {
        List<RemoteArea> sorted = remoteAreas.stream().sorted(BY_POSTAL_CODE_FROM).toList();
        for (int index = 1; index < sorted.size(); index++) {
            RemoteArea previous = sorted.get(index - 1);
            RemoteArea current = sorted.get(index);
            if (previous.overlaps(current)) {
                return Optional.of(new Overlap(previous, current));
            }
        }
        return Optional.empty();
    }

    /** 정책 통화 — 기본 배송비의 통화이고, 모든 금액이 같다. */
    public CurrencyCode currency() {
        return baseFee.currency();
    }

    /**
     * 주문 금액과 배송지 우편번호로 배송비를 계산한다.
     *
     * <ul>
     *   <li>무료배송 기준이 있고 주문 금액이 기준 <b>이상</b>이면 기본 배송비를 받지 않는다.</li>
     *   <li>도서산간 추가 배송비는 무료배송이어도 받는다.</li>
     * </ul>
     *
     * @param orderAmount 할인 후 상품 합계 (배송비 제외). 정책과 같은 통화여야 한다
     * @throws IllegalArgumentException 통화가 다를 때 — 호출자(Processor)가 먼저 도메인 오류로 거절한다
     * @throws IllegalStateException 저장되지 않은 정책(버전 없음)일 때 — 견적은 주문에 정책 버전을 스냅숏하므로 저장된 정책으로만 만든다
     */
    public ShippingFeeQuote quote(Money orderAmount, PostalCode postalCode) {
        Objects.requireNonNull(orderAmount, "orderAmount");
        Objects.requireNonNull(postalCode, "postalCode");
        if (version == null) {
            throw new IllegalStateException("저장되지 않은 배송비 정책으로 견적할 수 없습니다: id=" + id);
        }
        requireCurrency(currency(), orderAmount, "orderAmount");

        boolean freeShipping = freeShippingThreshold != null && !freeShippingThreshold.isGreaterThan(orderAmount);
        Optional<RemoteArea> remoteArea = remoteAreas.stream().filter(area -> area.contains(postalCode)).findFirst();
        Money surcharge = remoteArea.map(RemoteArea::surcharge).orElseGet(() -> Money.zero(currency()));
        Money fee = freeShipping ? Money.zero(currency()) : baseFee;

        return new ShippingFeeQuote(
            ShippingScope.DOMESTIC, baseFee, freeShippingThreshold, freeShipping,
            surcharge, remoteArea.map(RemoteArea::name).orElse(null), null, null, fee.plus(surcharge), version
        );
    }

    private static void requireCurrency(CurrencyCode expected, Money money, String label) {
        if (money.currency() != expected) {
            throw new IllegalArgumentException("배송비 정책의 금액은 통화가 같아야 합니다: %s 는 %s, 정책은 %s".formatted(label, money.currency(), expected));
        }
    }

    /** 겹친 두 도서산간 구간 — 시작 우편번호 순으로 앞 · 뒤. */
    public record Overlap(RemoteArea first, RemoteArea second) {
    }
}
