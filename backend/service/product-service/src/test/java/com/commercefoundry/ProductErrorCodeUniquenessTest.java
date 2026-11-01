package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayValidationMessage;
import com.commercefoundry.domainlayer.look.application.exception.LookValidationMessage;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartValidationMessage;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostValidationMessage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * product-service 는 여러 컨텍스트가 오류 코드 번호 공간 {@code PRODUCT_} 를 나눠 쓴다(상품 · 메인 전시 · 사이즈표 + 검증 메시지 클래스들).
 * 같은 번호가 두 뜻으로 쓰이면 FE · 운영자가 코드로 오류를 구분하지 못한다 — 번호 하나는 문구 하나다.
 * 서비스 공용 코드(page · size 등)는 여러 클래스에 같은 문구로 다시 적어도 된다.
 *
 * <p>#9 E1 에서 승인된 대역이 이미 쓰인 것을 늦게 발견해 더했다. 새 대역을 고를 때는 이 테스트가 먼저 실패한다.
 */
class ProductErrorCodeUniquenessTest {

    private static final List<Class<?>> VALIDATION_MESSAGE_CLASSES = List.of(
        ProductValidationMessage.class, DisplayValidationMessage.class, SizeChartValidationMessage.class, LookValidationMessage.class,
        SkuCostValidationMessage.class
    );

    @Test
    @DisplayName("같은 PRODUCT_ 번호는 어디서 쓰든 같은 문구다 — 업무 오류 코드는 검증 메시지 번호와 겹치지 않는다")
    void oneMeaningPerCode() {
        Map<String, Set<String>> messagesByCode = new TreeMap<>();
        Stream.of(ProductErrorCode.values()).forEach(code -> add(messagesByCode, code.getCode(), "error:" + code.getMessage()));
        Stream.of(DisplayErrorCode.values()).forEach(code -> add(messagesByCode, code.getCode(), "error:" + code.getMessage()));
        Stream.of(SizeChartErrorCode.values()).forEach(code -> add(messagesByCode, code.getCode(), "error:" + code.getMessage()));
        VALIDATION_MESSAGE_CLASSES.forEach(type -> validationMessagesOf(type).forEach(message -> {
            int colon = message.indexOf(':');
            add(messagesByCode, message.substring(0, colon), "validation:" + message.substring(colon + 1));
        }));

        List<String> conflicts = new ArrayList<>();
        messagesByCode.forEach((code, messages) -> {
            if (messages.size() > 1) {
                conflicts.add(code + " " + messages);
            }
        });
        assertThat(conflicts).as("한 번호가 두 뜻으로 쓰였다 — 빈 번호로 옮긴다").isEmpty();
    }

    private static void add(Map<String, Set<String>> messagesByCode, String code, String message) {
        if (code.startsWith("PRODUCT_")) {
            messagesByCode.computeIfAbsent(code, ignored -> new TreeSet<>()).add(message);
        }
    }

    /** 클래스의 {@code public static final String} 중 {@code "PRODUCT_nnn:문구"} 모양인 것. */
    private static List<String> validationMessagesOf(Class<?> type) {
        List<String> messages = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers) && field.getType() == String.class) {
                try {
                    String value = (String) field.get(null);
                    if (value.matches("PRODUCT_[0-9]{3}:.*")) {
                        messages.add(value);
                    }
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        }
        return messages;
    }
}
