package com.commercefoundry.domainlayer.product.adapter.in.web.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.DataHeader;
import com.commercefoundry.common.dto.ValidationErrorItem;
import com.commercefoundry.common.validation.ConstraintPriority;
import com.commercefoundry.common.validation.ConstraintPriority.Level;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.MethodValidationAdapter;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 본문과 파라미터 검증이 한 예외로 묶일 때, 값 파라미터의 {@code @ConstraintPriority} 가 옮겨 담은 뒤에도 지켜지는지 본다 (#270).
 */
class ProductExceptionHandlerTest {

    private final ProductExceptionHandler handler = new ProductExceptionHandler();

    @Test
    @DisplayName("@ConstraintPriority(LENGTH) 파라미터는 형식보다 앞이고 그 코드가 대표 코드다")
    void parameterConstraintPrioritySurvivesBodyRewrite() throws Exception {
        Method method = PriorityController.class.getMethod("adjust", String.class, Adjustment.class);
        MethodParameter[] parameters = parametersOf(method);
        MethodValidationResult result;
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            result = new MethodValidationAdapter(factory.getValidator()).validateArguments(
                new PriorityController(), method, parameters, new Object[] {"KEYWORD", new Adjustment("ok", "")}, new Class<?>[0]
            );
        }

        DataHeader header = handle(result);

        // keyword 는 본문 record 의 첫 컴포넌트라 그 파라미터가 정렬 1번이다. 길이 코드가 응답의 대표 코드가 된다.
        assertThat(header.resultCode()).isEqualTo("PRODUCT_181");
        assertThat(header.fieldErrors()).extracting(ValidationErrorItem::field).containsExactly("keyword", "keyword", "memo");
        assertThat(header.fieldErrors()).extracting(ValidationErrorItem::code).containsExactly("PRODUCT_181", "PRODUCT_182", "PRODUCT_183");
    }

    @Test
    @DisplayName("전역 오류와 원본 위반이 없는 오류는 500 없이 제약 이름으로 정렬한다")
    void globalErrorAndMissingViolationFallBackToConstraintName() throws Exception {
        Method method = EdgeController.class.getMethod("adjust", String.class, MemoBody.class);
        MethodParameter keyword = parametersOf(method)[0];
        MethodParameter request = parametersOf(method)[1];
        MessageSourceResolvable global = new ObjectError(
            "keyword", new String[] {"Pattern.keyword"}, null, "PRODUCT_192:형식이 올바르지 않습니다."
        );
        MessageSourceResolvable plain = new DefaultMessageSourceResolvable(new String[] {"NotBlank.keyword"}, "PRODUCT_191:필수입니다.");
        ParameterValidationResult value = new ParameterValidationResult(keyword, "BAD", List.of(global, plain), null, null, null, (error, sourceType) -> {
            if (error instanceof ObjectError) {
                throw new ClassCastException("FieldError");
            }
            throw new IllegalArgumentException("원본 위반 없음");
        });
        MemoBody body = new MemoBody("");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(body, "request");
        bindingResult.addError(new FieldError("request", "memo", "", false, new String[] {"NotBlank.memo"}, null, "PRODUCT_193:메모는 필수입니다."));
        ParameterErrors bean = new ParameterErrors(request, body, bindingResult, null, null, null);

        DataHeader header = handle(MethodValidationResult.create(new EdgeController(), method, List.of(bean, value)));

        assertThat(header.resultCode()).isEqualTo("PRODUCT_193");
        assertThat(header.fieldErrors()).extracting(ValidationErrorItem::code).containsExactly("PRODUCT_193", "PRODUCT_191", "PRODUCT_192");
    }

    private DataHeader handle(MethodValidationResult result) {
        return handler.handleHandlerMethodValidation(new HandlerMethodValidationException(result)).getBody().dataHeader();
    }

    private static MethodParameter[] parametersOf(Method method) {
        MethodParameter[] parameters = {new MethodParameter(method, 0), new MethodParameter(method, 1)};
        for (MethodParameter parameter : parameters) {
            parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        }
        return parameters;
    }

    /** 자리를 밝힌 커스텀 제약. 값은 항상 거절해 길이 코드와 형식 코드가 함께 나게 한다. */
    @ConstraintPriority(Level.LENGTH)
    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = DeclaredLengthValidator.class)
    @interface DeclaredLength {
        String message();

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class DeclaredLengthValidator implements ConstraintValidator<DeclaredLength, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return false;
        }
    }

    /**
     * 파라미터 이름을 본문 첫 컴포넌트와 같게 둔다. 그 필드가 선언 순서 0 이라, 길이 코드가 대표 코드가 되는지 볼 수 있다.
     */
    static final class PriorityController {
        public void adjust(
            @DeclaredLength(message = "PRODUCT_181:keyword 가 너무 깁니다.")
            @Pattern(regexp = "[a-z]+", message = "PRODUCT_182:keyword 형식이 올바르지 않습니다.")
            String keyword,
            @Valid Adjustment request
        ) {
        }
    }

    public record Adjustment(
        String keyword,
        @NotBlank(message = "PRODUCT_183:메모는 필수입니다.") String memo
    ) {
    }

    static final class EdgeController {
        public void adjust(String keyword, MemoBody request) {
        }
    }

    public record MemoBody(String memo) {
    }
}
