package com.commercefoundry.domainlayer.order.application.command;

import com.commercefoundry.domainlayer.order.domain.model.ShippingAddress;
import com.commercefoundry.shared.commerce.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 주문 생성. 배송지는 본문 스냅숏이다 — 회원 주소록(#7)이 아직 없다. 로그에 남기지 않는다 — {@link #toString()} 은 배송지를 가린다(배송지의 toString).
 *
 * @param items 1~{@value OrderQuoteCommand#MAX_LINES}줄, 요청 순서 (요청 검증이 거른다)
 * @param expectedTotalAmount 프론트가 보여 준 결제 금액. 없으면 null — 계산에는 쓰지 않고 대조만 한다
 */
public record OrderCreateCommand(List<OrderItemCommand> items, ShippingAddress shippingAddress, Money expectedTotalAmount) {

    public OrderCreateCommand {
        items = List.copyOf(items);
        Objects.requireNonNull(shippingAddress, "shippingAddress");
    }

    /** 주문서 계산 입력 — 미리보기와 같은 계산({@code OrderQuoteProcessor#quote})에 넘긴다. 배송 국가 · 우편번호는 배송지의 것이다. */
    public OrderQuoteCommand toQuoteCommand() {
        return new OrderQuoteCommand(items, shippingAddress.countryCode(), shippingAddress.postalCode());
    }

    /**
     * 같은 {@code Idempotency-Key} 의 재요청이 같은 본문인지 가리는 지문 — 정규화한 items(순서 · SKU · 수량 · 맞춤 제작 입력 해시) · 배송지 · 기대 금액을
     * 칸마다 {@code <길이>:<값>}(null 은 {@code -})으로 이은 UTF-8 바이트의 SHA-256 소문자 hex 64자. 길이를 앞에 붙여 칸 경계가 모호하지 않게 한다.
     * 기록에는 지문만 남고 원문은 남지 않는다. 지문도 로그에 남기지 않는다.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        append(canonical, String.valueOf(items.size()));
        for (OrderItemCommand item : items) {
            append(canonical, String.valueOf(item.skuId()));
            append(canonical, String.valueOf(item.quantity()));
            append(canonical, item.customOptions().hash());
        }
        append(canonical, shippingAddress.recipientName());
        append(canonical, shippingAddress.recipientPhone());
        append(canonical, shippingAddress.countryCode());
        append(canonical, shippingAddress.postalCode());
        append(canonical, shippingAddress.addressLine1());
        append(canonical, shippingAddress.addressLine2());
        append(canonical, shippingAddress.deliveryMemo());
        append(canonical, expectedTotalAmount == null ? null : expectedTotalAmount.amount() + expectedTotalAmount.currency().name());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", exception);
        }
    }

    private static void append(StringBuilder canonical, String value) {
        if (value == null) {
            canonical.append('-');
            return;
        }
        canonical.append(value.length()).append(':').append(value);
    }
}
