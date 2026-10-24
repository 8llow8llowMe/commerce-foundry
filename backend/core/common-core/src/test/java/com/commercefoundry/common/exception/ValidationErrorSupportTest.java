package com.commercefoundry.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.DataHeader;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.ValidationErrorItem;
import com.commercefoundry.common.validation.ConstraintPriority;
import com.commercefoundry.common.validation.ConstraintPriority.Level;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.MethodValidationAdapter;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ValidationErrorSupportTest {

    private static final String DEFAULT_CODE = "MEMBER_100";
    private static final String OBJECT_NAME = "signupRequest";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record SignupRequest(String email, String password, String nickname) {
    }

    private enum Currency { KRW, USD }

    private record CartLineRequest(String skuId, Integer quantity, Currency currency, List<CartLineRequest> addOns) {
    }

    // ── 요청 본문을 읽지 못한 경우 (HttpMessageNotReadableException) ─────────────

    @Test
    @DisplayName("enum 에 없는 값은 필드명과 허용 값을 함께 알려 준다 — 어디를 고칠지 알 수 있어야 한다")
    void unreadableBodyWithUnknownEnumValue() {
        DataHeader header = headerOf(unreadable("{\"skuId\":\"1\",\"currency\":\"JPY\"}"));

        assertThat(header.fieldErrors()).hasSize(1);
        assertThat(header.fieldErrors().getFirst().code()).isEqualTo(DEFAULT_CODE);
        assertThat(header.fieldErrors().getFirst().field()).isEqualTo("currency");
        assertThat(header.resultMessage()).contains("currency").contains("KRW, USD");
    }

    @Test
    @DisplayName("중첩 배열 안의 필드는 addOns[0].field 꼴 경로로 가리킨다")
    void unreadableBodyPointsNestedPath() {
        DataHeader header = headerOf(unreadable("{\"skuId\":\"1\",\"addOns\":[{\"skuId\":\"2\",\"currency\":\"JPY\"}]}"));

        assertThat(header.fieldErrors().getFirst().field()).isEqualTo("addOns[0].currency");
    }

    @Test
    @DisplayName("정수 범위를 넘는 값은 폴백 문구가 아니라 해당 필드를 짚는다")
    void unreadableBodyWithIntegerOverflow() {
        DataHeader header = headerOf(unreadable("{\"skuId\":\"1\",\"quantity\":2147483648}"));

        assertThat(header.fieldErrors().getFirst().field()).isEqualTo("quantity");
        assertThat(header.resultMessage()).contains("quantity").doesNotContain("JSON");
    }

    @Test
    @DisplayName("깨진 JSON 은 필드를 특정할 수 없어 request 로 두고 형식을 확인하라고 안내한다")
    void unreadableBodyWithBrokenJson() {
        DataHeader header = headerOf(unreadable("{\"skuId\": "));

        assertThat(header.fieldErrors().getFirst().field()).isEqualTo("request");
        assertThat(header.resultMessage()).contains("JSON");
    }

    /** 실제 Jackson 3 이 던지는 원인 예외를 그대로 감싼다 — Spring 의 메시지 컨버터가 하는 일과 같다. */
    private static HttpMessageNotReadableException unreadable(String json) {
        try {
            JSON.readValue(json, CartLineRequest.class);
        } catch (JacksonException cause) {
            return new HttpMessageNotReadableException("JSON parse error", cause, new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8)));
        }
        throw new IllegalStateException("역직렬화가 실패해야 하는 입력이다: " + json);
    }

    private static DataHeader headerOf(HttpMessageNotReadableException exception) {
        ResponseEntity<Response<Void>> response = ValidationErrorSupport.toResponse(exception, DEFAULT_CODE);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        Response<Void> payload = response.getBody();
        assertThat(payload).isNotNull();
        return payload.dataHeader();
    }

    // ── Bean Validation 실패 (MethodArgumentNotValidException) ─────────────────

    /** 직접 호출하지 않는다 — {@code MethodArgumentNotValidException} 이 요구하는 {@code MethodParameter} 를 리플렉션으로 얻는 자리다. */
    @SuppressWarnings("unused")
    private void handler(SignupRequest request) {
    }

    @Test
    @DisplayName("한 필드에 제약이 여러 개면 필수 → 길이 → 형식 순으로 정렬하고, 필드는 DTO 선언 순서를 따른다")
    void sortsByDeclarationOrderThenConstraintPriority() throws Exception {
        BindingResult bindingResult = bindingResult();
        // 일부러 뒤섞어서 넣는다. Bean Validation 은 제약 평가 순서를 보장하지 않는다.
        bindingResult.addError(fieldError("password", "Pattern", "MEMBER_105:문자 구성이 올바르지 않습니다."));
        bindingResult.addError(fieldError("nickname", "NotBlank", "MEMBER_108:닉네임은 필수입니다."));
        bindingResult.addError(fieldError("password", "Size", "MEMBER_104:8자 이상 20자 이하여야 합니다."));
        bindingResult.addError(fieldError("email", "Email", "MEMBER_102:이메일 형식이 올바르지 않습니다."));

        List<ValidationErrorItem> errors = headerOf(bindingResult).fieldErrors();

        assertThat(errors).extracting(ValidationErrorItem::code)
            .containsExactly("MEMBER_102", "MEMBER_104", "MEMBER_105", "MEMBER_108");
        assertThat(errors).extracting(ValidationErrorItem::field)
            .containsExactly("email", "password", "password", "nickname");
    }

    @Test
    @DisplayName("추가 순서가 달라도 같은 결과를 내려 resultCode 가 흔들리지 않는다")
    void producesSameResultRegardlessOfInsertionOrder() throws Exception {
        BindingResult first = bindingResult();
        first.addError(fieldError("password", "Size", "MEMBER_104:8자 이상 20자 이하여야 합니다."));
        first.addError(fieldError("password", "Pattern", "MEMBER_105:문자 구성이 올바르지 않습니다."));

        BindingResult second = bindingResult();
        second.addError(fieldError("password", "Pattern", "MEMBER_105:문자 구성이 올바르지 않습니다."));
        second.addError(fieldError("password", "Size", "MEMBER_104:8자 이상 20자 이하여야 합니다."));

        assertThat(headerOf(first).resultCode()).isEqualTo("MEMBER_104");
        assertThat(headerOf(second).resultCode()).isEqualTo("MEMBER_104");
        assertThat(headerOf(first).fieldErrors()).isEqualTo(headerOf(second).fieldErrors());
    }

    @Test
    @DisplayName("대표 메시지는 정렬된 첫 오류의 메시지이고 코드 접두어는 제거된다")
    void representativeMessageComesFromFirstSortedError() throws Exception {
        BindingResult bindingResult = bindingResult();
        bindingResult.addError(fieldError("password", "Pattern", "MEMBER_105:문자 구성이 올바르지 않습니다."));
        bindingResult.addError(fieldError("password", "NotBlank", "MEMBER_103:비밀번호는 필수입니다."));

        DataHeader header = headerOf(bindingResult);

        assertThat(header.resultMessage()).isEqualTo("비밀번호는 필수입니다.");
        assertThat(header.fieldErrors().getFirst().code()).isEqualTo("MEMBER_103");
    }

    @Test
    @DisplayName("코드 접두어가 없는 메시지는 호출부가 넘긴 기본 코드를 사용한다")
    void fallsBackToDefaultCodeWhenPrefixMissing() throws Exception {
        BindingResult bindingResult = bindingResult();
        bindingResult.addError(fieldError("email", "Email", "이메일 형식이 올바르지 않습니다."));

        List<ValidationErrorItem> errors = headerOf(bindingResult).fieldErrors();

        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().code()).isEqualTo(DEFAULT_CODE);
        assertThat(errors.getFirst().message()).isEqualTo("이메일 형식이 올바르지 않습니다.");
    }

    // ── 커스텀 제약의 정렬 자리 (@ConstraintPriority, #261) ─────────────────────────

    @ConstraintPriority(Level.LENGTH)
    @Target({ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = DeclaredLengthValidator.class)
    @interface DeclaredLength {
        String message();

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** 자리를 밝히지 않은 커스텀 제약. */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = UndeclaredValidator.class)
    @interface Undeclared {
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

    public static class UndeclaredValidator implements ConstraintValidator<Undeclared, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return false;
        }
    }

    private record ProfileRequest(
        @Email(message = "MEMBER_103:이메일 형식이 올바르지 않습니다.")
        @DeclaredLength(message = "MEMBER_102:이메일이 너무 깁니다.")
        String email,

        @Undeclared(message = "MEMBER_109:닉네임에 쓸 수 없는 문자가 있습니다.")
        @Pattern(regexp = "[a-z]+", message = "MEMBER_108:닉네임 형식이 올바르지 않습니다.")
        String nickname
    ) {
    }

    private static final ProfileRequest INVALID_PROFILE = new ProfileRequest("not-an-email", "NICK");

    @Test
    @DisplayName("@ConstraintPriority(LENGTH) 커스텀 제약은 형식(@Email)보다 앞이고, 자리를 밝히지 않은 제약은 형식(@Pattern)보다 뒤다 — 본문 검증")
    void customConstraintPriorityOnRequestBody() throws Exception {
        BindingResult bindingResult = new BeanPropertyBindingResult(INVALID_PROFILE, "profileRequest");
        // 실제 Spring 검증기로 만든 FieldError — 원본 ConstraintViolation 을 품는다
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            new SpringValidatorAdapter(factory.getValidator()).validate(INVALID_PROFILE, bindingResult);
        }

        List<ValidationErrorItem> errors = headerOf(bindingResult).fieldErrors();

        assertThat(errors).extracting(ValidationErrorItem::code).containsExactly("MEMBER_102", "MEMBER_103", "MEMBER_108", "MEMBER_109");
    }

    @Test
    @DisplayName("ConstraintViolationException 경로도 같은 자리로 정렬한다")
    void customConstraintPriorityOnConstraintViolations() {
        Set<ConstraintViolation<ProfileRequest>> violations;
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            violations = factory.getValidator().validate(INVALID_PROFILE);
        }

        ResponseEntity<Response<Void>> response = ValidationErrorSupport.toResponse(new ConstraintViolationException(violations), DEFAULT_CODE);
        DataHeader header = response.getBody().dataHeader();

        // 이 경로는 필드 선언 순서를 모른다 — 같은 필드 안의 자리만 본다
        assertThat(header.fieldErrors()).filteredOn(error -> error.field().equals("email"))
            .extracting(ValidationErrorItem::code).containsExactly("MEMBER_102", "MEMBER_103");
        assertThat(header.fieldErrors()).filteredOn(error -> error.field().equals("nickname"))
            .extracting(ValidationErrorItem::code).containsExactly("MEMBER_108", "MEMBER_109");
    }

    /** property node 없이 위반을 내는 클래스 수준 제약 — {@code @Valid} 빈의 전역 {@code ObjectError} 가 된다. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = GlobalCheckValidator.class)
    @interface GlobalCheck {
        String message();

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class GlobalCheckValidator implements ConstraintValidator<GlobalCheck, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @GlobalCheck(message = "MEMBER_120:요청 조합이 올바르지 않습니다.")
    public record FilterRequest(String sort) {
    }

    /** 직접 호출하지 않는다 — Spring MVC 메서드 검증이 보는 컨트롤러 메서드 자리다. */
    public static class SearchController {
        public void search(
            @DeclaredLength(message = "MEMBER_102:검색어가 너무 깁니다.")
            @Pattern(regexp = "[a-z]+", message = "MEMBER_103:검색어 형식이 올바르지 않습니다.")
            String keyword,
            @Valid FilterRequest filter
        ) {
        }
    }

    @Test
    @DisplayName("HandlerMethodValidationException 경로도 커스텀 제약 자리를 지키고, @Valid 빈의 전역 오류로 500 이 되지 않는다")
    void customConstraintPriorityOnHandlerMethodValidation() throws Exception {
        Method method = SearchController.class.getMethod("search", String.class, FilterRequest.class);
        MethodParameter[] parameters = {new MethodParameter(method, 0), new MethodParameter(method, 1)};
        for (MethodParameter parameter : parameters) {
            parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        }
        MethodValidationResult result;
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            result = new MethodValidationAdapter(factory.getValidator())
                .validateArguments(new SearchController(), method, parameters, new Object[] {"KEYWORD", new FilterRequest("x")}, new Class<?>[0]);
        }

        ResponseEntity<Response<Void>> response = ValidationErrorSupport.toResponse(new HandlerMethodValidationException(result), DEFAULT_CODE);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().dataHeader().fieldErrors()).extracting(ValidationErrorItem::code)
            .containsExactlyInAnyOrder("MEMBER_102", "MEMBER_103", "MEMBER_120")
            .containsSubsequence("MEMBER_102", "MEMBER_103");
    }

    // ── 필수 요청 파라미터 누락 (서블릿 MissingServletRequestParameterException) ──────

    @Test
    @DisplayName("필수 파라미터가 빠지면 호출부 코드로 400 을 내고 어느 파라미터인지 fieldErrors 로 짚는다")
    void missingRequestParameter() {
        ResponseEntity<Response<Void>> response = ValidationErrorSupport.toMissingParameterResponse("ids", "PRODUCT_199");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        DataHeader header = response.getBody().dataHeader();
        assertThat(header.resultCode()).isEqualTo("PRODUCT_199");
        assertThat(header.resultMessage()).isEqualTo("ids 파라미터는 필수입니다.");
        assertThat(header.fieldErrors()).containsExactly(new ValidationErrorItem("PRODUCT_199", "ids", "ids 파라미터는 필수입니다."));
    }

    // ── 응답 봉투의 타입 계약 (api-design-guide.md §2-1) ─────────────────────────

    /** 계약을 읽는 쪽이 보는 것은 JSON 이다. 직렬화 결과에서 {@code resultMessage} 가 문자열인지 고정한다. */
    @Test
    @DisplayName("검증 오류도 resultMessage 는 JSON 문자열이고 필드 목록은 fieldErrors 로 나간다")
    void validationFailureKeepsResultMessageAsJsonString() throws Exception {
        BindingResult bindingResult = bindingResult();
        bindingResult.addError(fieldError("email", "Email", "MEMBER_102:이메일 형식이 올바르지 않습니다."));

        JsonNode header = JSON.valueToTree(ValidationErrorSupport.toResponse(notValid(bindingResult), DEFAULT_CODE).getBody()).get("dataHeader");

        assertThat(header.get("resultMessage").isString()).isTrue();
        assertThat(header.get("resultMessage").asString()).isEqualTo("이메일 형식이 올바르지 않습니다.");
        assertThat(header.get("resultCode").asString()).isEqualTo("MEMBER_102");
        assertThat(header.get("fieldErrors").isArray()).isTrue();
        assertThat(header.get("fieldErrors").get(0).get("field").asString()).isEqualTo("email");
    }

    /** 검증이 아닌 오류와 <b>같은 타입</b>이어야 통일이다. 한쪽만 문자열이면 고친 것이 아니다. */
    @Test
    @DisplayName("검증이 아닌 오류는 resultMessage 타입이 같고 fieldErrors 만 null 이다")
    void nonValidationFailureHasNullFieldErrors() {
        JsonNode header = JSON.valueToTree(Response.fail("ORDER_001", "존재하지 않는 주문입니다.")).get("dataHeader");

        assertThat(header.get("resultMessage").isString()).isTrue();
        assertThat(header.get("fieldErrors").isNull()).isTrue();
    }

    private MethodArgumentNotValidException notValid(BindingResult bindingResult) throws Exception {
        Method method = getClass().getDeclaredMethod("handler", SignupRequest.class);
        return new MethodArgumentNotValidException(new MethodParameter(method, 0), bindingResult);
    }

    private BindingResult bindingResult() {
        return new BeanPropertyBindingResult(new SignupRequest("bad", "abc", ""), OBJECT_NAME);
    }

    /**
     * Spring 이 Bean Validation 실패로 만드는 FieldError 모양을 흉내낸다.
     * codes 의 첫 항목이 {@code "제약명.객체명.필드명"} 이라서 여기서 제약 애노테이션 이름을 뽑는다.
     */
    private FieldError fieldError(String field, String constraint, String message) {
        String[] codes = {constraint + "." + OBJECT_NAME + "." + field, constraint + "." + field, constraint};
        return new FieldError(OBJECT_NAME, field, null, false, codes, null, message);
    }

    private DataHeader headerOf(BindingResult bindingResult) throws Exception {
        ResponseEntity<Response<Void>> response = ValidationErrorSupport.toResponse(notValid(bindingResult), DEFAULT_CODE);
        Response<Void> payload = response.getBody();
        assertThat(payload).isNotNull();
        return payload.dataHeader();
    }
}
