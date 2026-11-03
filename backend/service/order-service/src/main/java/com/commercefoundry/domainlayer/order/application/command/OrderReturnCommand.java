package com.commercefoundry.domainlayer.order.application.command;

import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 본문 비교에는 정렬한 항목 값을 그대로 쓴다. 저장 식별자는 비교하지 않는다. */
public record OrderReturnCommand(List<Item> items) {
    public OrderReturnCommand {
        items = items.stream().sorted(Comparator.comparingLong(Item::orderItemId)).toList();
        if (items.isEmpty() || items.size() > 50) {
            throw new IllegalArgumentException("Invalid return item count");
        }
    }

    public boolean matches(List<ClaimItem> saved) {
        return items.equals(saved.stream().map(item -> new Item(item.orderItemId(), item.quantity(), item.reason())).toList());
    }

    public record Item(long orderItemId, int quantity, ClaimItem.Reason reason) {
        public Item {
            if (orderItemId <= 0 || quantity < 1 || quantity > 99) {
                throw new IllegalArgumentException("Invalid return item");
            }
            Objects.requireNonNull(reason, "reason");
        }
    }
}
