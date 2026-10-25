package com.commercefoundry.domainlayer.order.application.service.processor;

import com.commercefoundry.domainlayer.catalog.domain.model.CatalogCustomOrderField;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogCustomOrderInputType;
import com.commercefoundry.domainlayer.catalog.domain.model.CatalogSku;
import com.commercefoundry.domainlayer.order.application.command.OrderItemCommand;
import com.commercefoundry.domainlayer.order.application.exception.OrderFieldValidationException;
import com.commercefoundry.domainlayer.order.application.exception.OrderFieldValidationException.Violation;
import com.commercefoundry.domainlayer.order.application.exception.OrderValidationMessage;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 맞춤 제작 입력을 상품 항목 정의와 대조한다. 주문서 계산이 맞춤 제작이 아닌 줄의 입력을 거른 뒤에 부른다.
 * 입력값은 오류 메시지에 넣지 않는다.
 */
final class OrderCustomOptionRules {

    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(\\.\\d+)?");

    private OrderCustomOptionRules() {
    }

    static List<Violation> violations(List<OrderItemCommand> items, Map<Long, CatalogSku> skus) {
        List<Violation> violations = new ArrayList<>();
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            OrderItemCommand item = items.get(itemIndex);
            CatalogSku sku = skus.get(item.skuId());
            if (sku == null || !sku.customOrder()) {
                continue;
            }
            Map<String, CatalogCustomOrderField> definitions = definitionsOf(sku);
            List<OrderCustomOption> options = item.customOptions().values();
            for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
                OrderCustomOption option = options.get(optionIndex);
                String field = valueField(itemIndex, optionIndex);
                CatalogCustomOrderField definition = definitions.get(option.fieldKey());
                if (definition == null) {
                    violations.add(violation(OrderValidationMessage.CUSTOM_ORDER_FIELD_UNKNOWN, field));
                    continue;
                }
                checkValue(violations, field, definition, option.value());
            }
            int missingIndex = options.size();
            for (CatalogCustomOrderField definition : definitions.values()) {
                if (!definition.required() || containsKey(options, definition.fieldKey())) {
                    continue;
                }
                violations.add(violation(OrderValidationMessage.CUSTOM_ORDER_FIELD_REQUIRED, valueField(itemIndex, missingIndex)));
                missingIndex++;
            }
        }
        return violations;
    }

    private static Map<String, CatalogCustomOrderField> definitionsOf(CatalogSku sku) {
        Map<String, CatalogCustomOrderField> definitions = new LinkedHashMap<>();
        for (CatalogCustomOrderField field : sku.customOrderFields()) {
            definitions.putIfAbsent(field.fieldKey(), field);
        }
        return definitions;
    }

    private static void checkValue(List<Violation> violations, String field, CatalogCustomOrderField definition, String value) {
        if (definition.inputType() == CatalogCustomOrderInputType.NUMBER) {
            if (!NUMBER.matcher(value).matches()) {
                violations.add(violation(OrderValidationMessage.CUSTOM_ORDER_NUMBER_FORMAT, field));
                return;
            }
            BigDecimal number = new BigDecimal(value);
            if ((definition.min() != null && number.compareTo(definition.min()) < 0)
                || (definition.max() != null && number.compareTo(definition.max()) > 0)) {
                violations.add(violation(OrderValidationMessage.CUSTOM_ORDER_NUMBER_RANGE, field));
                return;
            }
            if (definition.step() != null && definition.step().signum() > 0 && !onStep(number, definition)) {
                violations.add(violation(OrderValidationMessage.CUSTOM_ORDER_NUMBER_STEP, field));
            }
            return;
        }
        if (definition.max() != null && value.codePointCount(0, value.length()) > definition.max().intValue()) {
            violations.add(violation(OrderValidationMessage.CUSTOM_ORDER_TEXT_LENGTH, field));
        }
    }

    /** 기준은 최소값이고, 최소값이 없으면 0 이다. */
    private static boolean onStep(BigDecimal number, CatalogCustomOrderField definition) {
        BigDecimal origin = definition.min() == null ? BigDecimal.ZERO : definition.min();
        BigDecimal remainder = number.subtract(origin).remainder(definition.step());
        return remainder.stripTrailingZeros().compareTo(BigDecimal.ZERO) == 0;
    }

    private static boolean containsKey(List<OrderCustomOption> options, String fieldKey) {
        for (OrderCustomOption option : options) {
            if (option.fieldKey().equals(fieldKey)) {
                return true;
            }
        }
        return false;
    }

    private static String valueField(int itemIndex, int optionIndex) {
        return "items[%d].customOptions[%d].value".formatted(itemIndex, optionIndex);
    }

    private static Violation violation(String codedMessage, String field) {
        int delimiter = codedMessage.indexOf(':');
        return new Violation(codedMessage.substring(0, delimiter), field, codedMessage.substring(delimiter + 1));
    }
}
