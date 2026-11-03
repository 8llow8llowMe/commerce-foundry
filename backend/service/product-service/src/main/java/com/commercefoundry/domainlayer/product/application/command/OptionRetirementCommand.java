package com.commercefoundry.domainlayer.product.application.command;

import java.util.Set;

/** 옵션 그룹 · 값 은퇴 (#9 PR-C2). 아이디는 중복 없이 받는다. */
public record OptionRetirementCommand(long productId, long version, long actorMemberId, Set<Long> optionGroupIds, Set<Long> optionValueIds) {

    public OptionRetirementCommand {
        optionGroupIds = Set.copyOf(optionGroupIds);
        optionValueIds = Set.copyOf(optionValueIds);
    }
}
