package com.commercefoundry.domainlayer.product.application.command;

import java.util.List;

public record ProductOptionsUpdateCommand(long productId, long version, long actorMemberId, List<Group> optionGroups) {
    public record Group(long optionGroupId, String name, List<Value> values) { }
    public record Value(long optionValueId, String name) { }
}
