package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderCommand;
import com.commercefoundry.domainlayer.product.application.command.ProductCustomOrderUpdateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.info.ProductAdminDetailInfo;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderInputType;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 맞춤 제작 설정 (#9 PR-B) — 상품의 사용 여부 · 안내 · 소요일과 입력 항목 정의.
 *
 * <p>항목 정의는 주문 생성이 고객 입력과 대조하는 계약이다({@code GET /internal/v1/skus} 의 {@code customOrderFields},
 * order-flow-guide §2-2 4번). 주문 · 장바구니는 {@code fieldKey} 와 값만 남기므로 같은 키는 같은 항목(아이디 유지)이고, 키를 바꾸는 편집은 없다 —
 * 빠진 키는 지우고 새 키는 새 항목이다. 이미 접수된 주문의 입력값은 주문 쪽 스냅숏이라 정의를 바꿔도 그대로다.
 * 장바구니에 담긴 입력은 주문할 때 지금 정의로 다시 검증된다(지운 키 · 꺼진 맞춤 제작은 주문에서 400).
 */
@Component
@RequiredArgsConstructor
public class ProductCustomOrderProcessor {

    /** {@code product_custom_order_field.min_value} 등 {@code numeric(10, 2)} 의 범위. */
    private static final BigDecimal NUMBER_LIMIT = new BigDecimal("100000000");
    private static final int NUMBER_SCALE = 2;
    /** 주문 · 장바구니 입력값 칸이 500자다. */
    private static final int TEXT_MAX_LENGTH = 500;

    private final ProductRepositoryPort productRepositoryPort;
    private final ProductCustomOrderFieldRepositoryPort productCustomOrderFieldRepositoryPort;
    private final ProductAdminDetailProcessor productAdminDetailProcessor;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 저장 없이 볼 수 있는 구성 규칙. 등록은 카테고리 잠금 전에, 수정은 상품을 읽기 전에 부른다.
     *
     * @throws ProductException {@code PRODUCT_011}
     */
    public void validate(ProductCustomOrderCommand settings) {
        var keys = new HashSet<String>();
        for (var field : settings.fields()) {
            if (!keys.add(field.fieldKey())) {
                invalid("같은 입력 항목 키가 두 번 있습니다(" + field.fieldKey() + ")");
            }
            if (field.inputType() == CustomOrderInputType.NUMBER) {
                validateNumber(field);
            } else {
                validateText(field);
            }
        }
    }

    /** 새 상품의 항목 정의 — 등록 트랜잭션 안에서 상품 INSERT 뒤에 부른다. 판정은 {@link #validate} 가 끝냈다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertForNewProduct(long productId, ProductCustomOrderCommand settings) {
        var fields = fieldsOf(productId, settings, Map.of());
        if (!fields.isEmpty()) {
            productCustomOrderFieldRepositoryPort.replace(productId, List.of(), fields);
        }
    }

    /**
     * 설정 전체 교체. 버전이 다르면 409 {@code PRODUCT_010}, 지운 상품은 404 {@code PRODUCT_001} 이다.
     * 바뀐 것이 없으면 아무것도 쓰지 않는다(버전 유지). 상품 칸이 바뀌면 상품 merge 가, 항목만 바뀌면 버전 CAS 가 자식 쓰기보다 먼저 동시 편집을 막는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductAdminDetailInfo update(ProductCustomOrderUpdateCommand command) {
        var settings = command.settings();
        validate(settings);
        Product current = productRepositoryPort.findAllByIdsIncludingHidden(List.of(command.productId())).stream().findFirst()
            .filter(product -> !product.isDeleted())
            .orElseThrow(() -> new ProductException(ProductErrorCode.NOT_FOUND_PRODUCT));
        if (current.version() == null || current.version() != command.version()) {
            throw new ProductException(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT);
        }
        var existing = productCustomOrderFieldRepositoryPort.findAllByProductId(command.productId());
        var next = fieldsOf(command.productId(), settings,
            existing.stream().collect(Collectors.toMap(CustomOrderField::fieldKey, Function.identity())));
        boolean headerChanged = current.customOrder() != settings.enabled()
            || !Objects.equals(current.customOrderNotice(), settings.notice())
            || !Objects.equals(current.customOrderLeadTimeDays(), settings.leadTimeDays());
        boolean fieldsChanged = !sameFields(existing, next);
        if (headerChanged) {
            productRepositoryPort.update(withCustomOrder(current, settings));
        } else if (fieldsChanged) {
            productRepositoryPort.bumpVersion(command.productId(), command.version());
        }
        if (fieldsChanged) {
            productCustomOrderFieldRepositoryPort.replace(command.productId(), existing, next);
        }
        return productAdminDetailProcessor.getProduct(command.productId());
    }

    private List<CustomOrderField> fieldsOf(long productId, ProductCustomOrderCommand settings, Map<String, CustomOrderField> existingByKey) {
        var fields = new ArrayList<CustomOrderField>();
        for (var input : settings.fields()) {
            var kept = existingByKey.get(input.fieldKey());
            long id = kept == null ? snowflakeIdGenerator.generateId() : kept.id();
            fields.add(new CustomOrderField(id, productId, input.fieldKey(), input.label(), input.inputType(), input.unit(), input.required(),
                normalized(input.minValue()), normalized(input.maxValue()), normalized(input.stepValue()), input.helpText(), fields.size()));
        }
        return fields;
    }

    /** 순서까지 같은가 — {@code numeric} 은 크기로 비교한다(60 과 60.00 은 같다). */
    private static boolean sameFields(List<CustomOrderField> existing, List<CustomOrderField> next) {
        if (existing.size() != next.size()) {
            return false;
        }
        var byId = existing.stream().collect(Collectors.toMap(CustomOrderField::id, Function.identity()));
        for (var field : next) {
            var old = byId.get(field.id());
            if (old == null || old.displayOrder() != field.displayOrder() || !old.label().equals(field.label())
                || old.inputType() != field.inputType() || old.required() != field.required()
                || !Objects.equals(old.unit(), field.unit()) || !Objects.equals(old.helpText(), field.helpText())
                || !sameNumber(old.minValue(), field.minValue()) || !sameNumber(old.maxValue(), field.maxValue())
                || !sameNumber(old.stepValue(), field.stepValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static Product withCustomOrder(Product current, ProductCustomOrderCommand settings) {
        return new Product(current.id(), current.categoryId(), current.name(), current.summary(), current.descriptionHtml(),
            current.saleStatus(), current.visible(), current.salePrice(), current.listPrice(), current.maxQuantityPerOrder(),
            settings.enabled(), settings.notice(), settings.leadTimeDays(), current.preorder(),
            current.expectedShipDate(), current.version(), current.deletedAt(), current.createdByMemberId(),
            current.nameEn(), current.summaryEn(), current.descriptionHtmlEn());
    }

    private static void validateNumber(ProductCustomOrderCommand.Field field) {
        for (var value : new BigDecimal[] {field.minValue(), field.maxValue(), field.stepValue()}) {
            if (value != null && (value.abs().compareTo(NUMBER_LIMIT) >= 0 || normalized(value).scale() > NUMBER_SCALE)) {
                invalid("숫자 입력(" + field.fieldKey() + ")의 최소 · 최대 · 증분은 소수 둘째 자리까지, 1억 미만이어야 합니다");
            }
        }
        if (field.minValue() != null && field.maxValue() != null && field.minValue().compareTo(field.maxValue()) > 0) {
            invalid("숫자 입력(" + field.fieldKey() + ")의 최소값이 최대값보다 큽니다");
        }
        if (field.stepValue() != null && field.stepValue().signum() <= 0) {
            invalid("숫자 입력(" + field.fieldKey() + ")의 증분은 0보다 커야 합니다");
        }
    }

    private static void validateText(ProductCustomOrderCommand.Field field) {
        if (field.minValue() != null || field.stepValue() != null) {
            invalid("글자 입력(" + field.fieldKey() + ")에는 최소값 · 증분을 쓰지 않습니다");
        }
        var max = field.maxValue();
        if (max != null && (normalized(max).scale() > 0 || max.compareTo(BigDecimal.ONE) < 0
            || max.compareTo(BigDecimal.valueOf(TEXT_MAX_LENGTH)) > 0)) {
            invalid("글자 입력(" + field.fieldKey() + ")의 최대 글자 수는 1~" + TEXT_MAX_LENGTH + " 사이 정수여야 합니다");
        }
    }

    /** 끝자리 0 을 뺀다(60.00 → 60). 정수의 음수 scale 은 0 으로 되돌린다. */
    private static BigDecimal normalized(BigDecimal value) {
        if (value == null) {
            return null;
        }
        var stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static void invalid(String reason) {
        throw new ProductException(ProductErrorCode.PRODUCT_CUSTOM_ORDER_INVALID, reason);
    }
}
