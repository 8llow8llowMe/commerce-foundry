package com.commercefoundry.domainlayer.product.application.command;

import java.util.List;

public record ProductOptionsUpdateCommand(long productId, long version, long actorMemberId, List<Group> optionGroups) {
    public record Group(long optionGroupId, String name, List<Value> values, String nameEn) {
        public Group(long optionGroupId, String name, List<Value> values) {
            this(optionGroupId, name, values, null);
        }
    }
    public record Value(long optionValueId, String name, String nameEn) {
        public Value(long optionValueId, String name) {
            this(optionValueId, name, null);
        }
    }
}
