package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderCommand.Field;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 주문이 받을 수 없는 맞춤 입력 정의를 저장 전에 막는다 (#9 PR-B, order-flow-guide §2-2 4번). */
class ProductCustomOrderProcessorTest {

    private final ProductCustomOrderProcessor processor = new ProductCustomOrderProcessor(
        mock(ProductRepositoryPort.class), mock(ProductCustomOrderFieldRepositoryPort.class), mock(ProductAdminDetailProcessor.class),
        mock(SnowflakeIdGenerator.class));

    private static BigDecimal number(String value) {
        return value == null || value.isEmpty() ? null : new BigDecimal(value);
    }

    private static Field numberField(String key, String min, String max, String step) {
        return new Field(key, "치수", CustomOrderInputType.NUMBER, "cm", true, number(min), number(max), number(step), null);
    }

    private static Field textField(String key, String min, String max, String step) {
        return new Field(key, "요청", CustomOrderInputType.TEXT, null, false, number(min), number(max), number(step), null);
    }

    private static void assertInvalid(List<Field> fields) {
        assertThatThrownBy(() -> processor().validate(new ProductCustomOrderCommand(true, null, null, fields)))
            .isInstanceOfSatisfying(ProductException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_CUSTOM_ORDER_INVALID));
    }

    private static ProductCustomOrderProcessor processor() {
        return new ProductCustomOrderProcessor(mock(ProductRepositoryPort.class), mock(ProductCustomOrderFieldRepositoryPort.class),
            mock(ProductAdminDetailProcessor.class), mock(SnowflakeIdGenerator.class));
    }

    @Test
    @DisplayName("주문 검증이 받는 정의는 통과한다 — 범위 · 증분이 없는 숫자, 최대 글자 수가 없는 글자, 항목 없음")
    void acceptsDefinitionsOrderUnderstands() {
        assertThatCode(() -> processor.validate(new ProductCustomOrderCommand(true, null, null, List.of(
            numberField("chest", "60", "150", "0.5"), numberField("sleeve", null, null, null),
            textField("memo", null, "500", null), textField("note", null, null, null))))).doesNotThrowAnyException();
        assertThatCode(() -> processor.validate(ProductCustomOrderCommand.disabled())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 키가 두 번이면 400 PRODUCT_011")
    void duplicatedKey() {
        assertInvalid(List.of(numberField("chest", null, null, null), textField("chest", null, null, null)));
    }

    @ParameterizedTest(name = "숫자 min={0} max={1} step={2}")
    @CsvSource({
        "150, 60, ",          // 최소 > 최대
        ", , 0",              // 증분 0
        ", , -0.5",           // 음수 증분
        "0.001, , ",          // 소수 셋째 자리
        ", 100000000, ",      // numeric(10, 2) 범위 밖
    })
    @DisplayName("숫자 범위 · 증분 · 자릿수가 맞지 않으면 400 PRODUCT_011")
    void invalidNumber(String min, String max, String step) {
        assertInvalid(List.of(numberField("chest", min, max, step)));
    }

    @ParameterizedTest(name = "글자 min={0} max={1} step={2}")
    @CsvSource({
        "1, 100, ",   // 최소값 지정
        ", 100, 1",   // 증분 지정
        ", 0, ",      // 0자
        ", 501, ",    // 주문 입력 칸(500자)보다 큼
        ", 10.5, ",   // 정수가 아님
    })
    @DisplayName("글자 입력의 최소값 · 증분 지정, 1~500 정수가 아닌 최대 글자 수는 400 PRODUCT_011")
    void invalidText(String min, String max, String step) {
        assertInvalid(List.of(textField("memo", min, max, step)));
    }

    @Test
    @DisplayName("끝자리 0 은 자릿수로 세지 않는다 — 60.000 · 500.00 은 통과")
    void trailingZerosAreNotScale() {
        assertThatCode(() -> processor.validate(new ProductCustomOrderCommand(true, null, null, List.of(
            numberField("chest", "60.000", null, null), textField("memo", null, "500.00", null))))).doesNotThrowAnyException();
    }
}
