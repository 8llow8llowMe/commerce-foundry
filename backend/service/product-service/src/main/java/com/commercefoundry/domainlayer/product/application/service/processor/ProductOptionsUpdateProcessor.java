package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductOptionsUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 기존 옵션의 이름과 순서만 편집하고 아이디·조합·은퇴 상태를 보존한다. */
@Component
@RequiredArgsConstructor
public class ProductOptionsUpdateProcessor {
    private final ProductRepositoryPort productRepositoryPort;
    private final ProductOptionRepositoryPort productOptionRepositoryPort;
    private final ProductAdminDetailProcessor productAdminDetailProcessor;

    @Transactional(propagation = Propagation.MANDATORY)
    public ProductAdminDetailInfo update(ProductOptionsUpdateCommand command) {
        var product = productRepositoryPort.findAllByIdsIncludingHidden(List.of(command.productId())).stream().findFirst()
            .filter(current -> !current.isDeleted()).orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        if (product.version() == null || product.version() != command.version()) {
            throw new ProductException(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        }
        var existingGroups = productOptionRepositoryPort.findGroupsByProductId(command.productId());
        var existingValues = productOptionRepositoryPort.findValuesByProductId(command.productId());
        var groupsById = existingGroups.stream().collect(Collectors.toMap(OptionGroup::id, Function.identity()));
        var valuesById = existingValues.stream().collect(Collectors.toMap(OptionValue::id, Function.identity()));
        var groupIds = new HashSet<Long>();
        var valueIds = new HashSet<Long>();
        var groupNames = new HashSet<String>();
        var groups = new ArrayList<OptionGroup>();
        var values = new ArrayList<OptionValue>();
        for (var input : command.optionGroups()) {
            var group = groupsById.get(input.optionGroupId());
            if (group == null || !groupIds.add(input.optionGroupId()) || !groupNames.add(input.name().strip())) invalid();
            groups.add(new OptionGroup(group.id(), group.productId(), input.name().strip(), groups.size(), group.retiredAt()));
            var valueNames = new HashSet<String>();
            for (int order = 0; order < input.values().size(); order++) {
                var valueInput = input.values().get(order);
                var value = valuesById.get(valueInput.optionValueId());
                if (value == null || value.optionGroupId() != group.id() || !valueIds.add(valueInput.optionValueId())
                    || !valueNames.add(valueInput.name().strip())) invalid();
                values.add(new OptionValue(value.id(), group.id(), value.productId(), valueInput.name().strip(), order, value.retiredAt()));
            }
        }
        if (!groupIds.equals(groupsById.keySet()) || !valueIds.equals(valuesById.keySet())) invalid();
        boolean changed = groups.stream().anyMatch(group -> !group.equals(groupsById.get(group.id())))
            || values.stream().anyMatch(value -> !value.equals(valuesById.get(value.id())));
        if (changed) {
            // CAS precedes every child write and fences concurrent header/pricing/category/image/SKU edits and deletion.
            productRepositoryPort.bumpVersion(command.productId(), command.version());
            productOptionRepositoryPort.updateLabels(existingGroups, existingValues, groups, values);
        }
        return productAdminDetailProcessor.getProduct(command.productId());
    }

    private static void invalid() {
        throw new ProductException(ProductErrorCode.PRODUCT_OPTION_COMBINATION_INVALID, "기존 옵션 전체의 아이디·소속과 중복 없는 이름을 확인해 주세요");
    }
}
