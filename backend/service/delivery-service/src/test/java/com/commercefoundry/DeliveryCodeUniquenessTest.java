package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentErrorCode;
import com.commercefoundry.domainlayer.shipment.application.exception.ShipmentValidationMessage;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryValidationMessage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * delivery-service 의 {@code DELIVERY_} 코드는 컨텍스트별 enum · 검증 메시지가 번호를 나눠 쓴다(001~013 배송비, 014~ 송장, 검증 101~139 배송비 · 140~ 송장).
 * 같은 번호가 두 군데 생기면 운영자 · FE 가 코드만으로 원인을 가르지 못한다 — 네 곳의 합집합에 중복이 없는지 본다.
 */
class DeliveryCodeUniquenessTest {

    private static List<String> validationCodes(Class<?> messages) {
        List<String> codes = new ArrayList<>();
        for (Field field : messages.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                try {
                    String message = (String) field.get(null);
                    codes.add(message.substring(0, message.indexOf(':')));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        }
        return codes;
    }

    @Test
    @DisplayName("두 오류 코드 enum 과 두 검증 메시지의 코드 합집합에 중복이 없다")
    void codesAreUnique() {
        List<String> codes = Stream.of(
            Arrays.stream(DeliveryErrorCode.values()).map(DeliveryErrorCode::getCode),
            Arrays.stream(ShipmentErrorCode.values()).map(ShipmentErrorCode::getCode),
            validationCodes(DeliveryValidationMessage.class).stream(),
            validationCodes(ShipmentValidationMessage.class).stream()
        ).flatMap(stream -> stream).toList();

        assertThat(codes).allMatch(code -> code.matches("DELIVERY_\\d{3}"));
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).contains("DELIVERY_013", "DELIVERY_014", "DELIVERY_139", "DELIVERY_140");
    }
}
