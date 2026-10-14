package com.commercefoundry.common.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.dto.ValidationErrorItem;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * 검증 실패를 필드별 에러코드가 담긴 응답으로 변환한다 ({@code coding-conventions.md} §8-2).
 *
 * <p>검증 메시지는 {@code "DOMAIN_101:사용자에게 보여줄 메시지"} 규약을 사용한다.
 * 코드를 앞에 두면 DTO 선언 옆에서 코드를 바로 확인할 수 있고, 필드가 늘어도 매핑 테이블을
 * 따로 관리하지 않는다. 코드 접두어가 없는 메시지는 호출부가 넘긴 기본 코드를 사용한다.
 *
 * <p>한 필드에 제약이 여러 개 걸리면 오류도 여러 개 나온다(예: 비밀번호의 길이와 문자 구성).
 * 오류를 버리지 않고 모두 담되, <b>순서를 고정</b>해서 내려준다. Bean Validation 스펙은 제약
 * 평가 순서를 보장하지 않으므로 정렬하지 않으면 같은 요청에 resultCode 가 바뀔 수 있다.
 *
 * <p>정렬 기준은 (1) DTO 선언 순서 → (2) 제약 우선순위(필수 → 길이 → 범위 → 형식) →
 * (3) 메시지 순이다. resultCode 와 대표 메시지는 정렬된 첫 번째 오류를 사용한다.
 *
 * <p><b>응답 모양은 다른 오류와 같다</b> — 대표 메시지는 {@code dataHeader.resultMessage} 에
 * 문자열로, 필드별 목록은 {@code dataHeader.fieldErrors} 에 담긴다.
 */
public final class ValidationErrorSupport {

    private static final String CODE_DELIMITER = ":";
    private static final String UNKNOWN_FIELD = "request";

    /**
     * 제약 애노테이션별 표시 우선순위. 값이 작을수록 먼저 보여준다.
     * 값이 비어 있는지(필수) → 길이 → 범위 → 형식 순으로, 사용자가 먼저 고쳐야 하는 것을 앞에 둔다.
     */
    private static final Map<String, Integer> CONSTRAINT_PRIORITY = Map.ofEntries(
        Map.entry("NotNull", 0),
        Map.entry("NotBlank", 0),
        Map.entry("NotEmpty", 0),
        Map.entry("Size", 1),
        Map.entry("Length", 1),
        Map.entry("Min", 2),
        Map.entry("Max", 2),
        Map.entry("DecimalMin", 2),
        Map.entry("DecimalMax", 2),
        Map.entry("Positive", 2),
        Map.entry("PositiveOrZero", 2),
        Map.entry("Negative", 2),
        Map.entry("NegativeOrZero", 2),
        Map.entry("Range", 2),
        Map.entry("Email", 3),
        Map.entry("Pattern", 3),
        Map.entry("URL", 3)
    );
    private static final int UNKNOWN_CONSTRAINT_PRIORITY = 9;

    private ValidationErrorSupport() {
    }

    public static ResponseEntity<Response<Void>> toResponse(MethodArgumentNotValidException exception, String defaultCode) {
        List<RawError> rawErrors = new ArrayList<>();
        for (ObjectError error : exception.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            rawErrors.add(new RawError(field, error.getDefaultMessage(), resolveConstraint(error)));
        }
        // record DTO 면 컴포넌트 선언 순서를 알 수 있어 필드 순서까지 고정할 수 있다.
        return build(rawErrors, resolveFieldOrder(exception.getBindingResult().getTarget()), defaultCode);
    }

    public static ResponseEntity<Response<Void>> toResponse(ConstraintViolationException exception, String defaultCode) {
        List<RawError> rawErrors = new ArrayList<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            rawErrors.add(new RawError(resolveLeafField(violation), violation.getMessage(), resolveConstraint(violation)));
        }
        return build(rawErrors, Map.of(), defaultCode);
    }

    public static ResponseEntity<Response<Void>> toResponse(HandlerMethodValidationException exception, String defaultCode) {
        // 컨트롤러 파라미터(@RequestParam 등) 단위 검증 결과에서 필드명과 메시지를 수집한다.
        List<RawError> rawErrors = new ArrayList<>();
        exception.getParameterValidationResults().forEach(result -> {
            String parameterName = result.getMethodParameter().getParameterName();
            String field = parameterName == null ? UNKNOWN_FIELD : parameterName;
            result.getResolvableErrors()
                .forEach(error -> rawErrors.add(new RawError(field, error.getDefaultMessage(), resolveConstraint(error))));
        });
        return build(rawErrors, Map.of(), defaultCode);
    }

    public static ResponseEntity<Response<Void>> toResponse(MethodArgumentTypeMismatchException exception, String defaultCode) {
        String message = "%s 파라미터 형식이 올바르지 않습니다.".formatted(exception.getName());
        return respond(List.of(new ValidationErrorItem(defaultCode, exception.getName(), message)));
    }

    /**
     * 요청 본문을 읽지 못한 경우 — 깨진 JSON, enum 에 없는 값({@code currency: "JPY"}), 타입 불일치, 정수 범위 초과.
     *
     * <p>Bean Validation 보다 앞선 역직렬화 단계라 필드별 코드가 없다. 도메인 기본 코드({@code {DOMAIN}_100})로
     * 응답하되 <b>어느 필드인지는 밝힌다</b> — 필드가 열 개인 주문서에서 "본문을 읽을 수 없다" 만 받으면 무엇을
     * 고쳐야 할지 알 수 없다. enum 이면 허용 값도 함께 준다. 핸들러가 없으면 Spring 기본 400 이 {@code Response}
     * 봉투 없이 나가 클라이언트 파서가 깨진다.
     */
    public static ResponseEntity<Response<Void>> toResponse(HttpMessageNotReadableException exception, String defaultCode) {
        Throwable cause = exception.getCause();
        // InvalidFormat 이 MismatchedInput 의 하위라 먼저 본다.
        if (cause instanceof InvalidFormatException invalidFormat) {
            String field = fieldPathOf(invalidFormat);
            return respond(List.of(new ValidationErrorItem(defaultCode, field, describeInvalidFormat(invalidFormat, field))));
        }
        if (cause instanceof MismatchedInputException mismatched) {
            String field = fieldPathOf(mismatched);
            return respond(List.of(new ValidationErrorItem(defaultCode, field, "%s 값의 형식이 올바르지 않습니다.".formatted(field))));
        }
        // 정수 범위 초과(`quantity: 2147483648`). 스트림 단계 예외라 위 두 분기에 걸리지 않는다.
        // 폴백으로 떨어지면 개발자용 문구가 필드도 못 짚은 채 사용자 화면에 뜬다.
        if (cause instanceof InputCoercionException coercion) {
            String field = fieldPathOf(coercion);
            return respond(List.of(new ValidationErrorItem(defaultCode, field, "%s 값이 허용 범위를 벗어났습니다.".formatted(field))));
        }
        return respond(List.of(new ValidationErrorItem(defaultCode, UNKNOWN_FIELD, "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해 주세요.")));
    }

    /**
     * Jackson 경로({@code items[0].quantity})를 필드명으로 만든다.
     *
     * <p>경로가 비어 있으면 파서가 마지막으로 읽던 필드 이름을 쓴다 — 스트림 단계 예외는 경로가 채워지지 않을 수 있고,
     * 그 예외가 나는 자리가 곧 문제의 필드다. 둘 다 없으면 본문 전체를 가리키는 {@code request} 다.
     */
    private static String fieldPathOf(JacksonException exception) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : exception.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        if (!path.isEmpty()) {
            return path.toString();
        }
        return currentFieldOf(exception);
    }

    private static String currentFieldOf(JacksonException exception) {
        if (!(exception instanceof StreamReadException streamRead)) {
            return UNKNOWN_FIELD;
        }
        JsonParser parser = streamRead.processor();
        if (parser == null) {
            return UNKNOWN_FIELD;
        }
        String name = parser.currentName();
        return name == null || name.isBlank() ? UNKNOWN_FIELD : name;
    }

    private static String describeInvalidFormat(InvalidFormatException exception, String field) {
        Class<?> targetType = exception.getTargetType();
        if (targetType != null && targetType.isEnum()) {
            String allowed = Arrays.stream(targetType.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name())
                .collect(Collectors.joining(", "));
            return "%s 값이 올바르지 않습니다. 허용 값: %s".formatted(field, allowed);
        }
        return "%s 값의 형식이 올바르지 않습니다.".formatted(field);
    }

    /**
     * 제약 애노테이션 단순명을 뽑아낸다. Spring 은 코드를 {@code "Size.dto.field"} 처럼 만들어 두므로
     * 첫 마디만 취하면 애노테이션 이름이 된다.
     */
    private static String resolveConstraint(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        if (codes == null || codes.length == 0 || codes[0] == null) {
            return "";
        }
        String code = codes[0];
        int dotIndex = code.indexOf('.');
        return dotIndex < 0 ? code : code.substring(0, dotIndex);
    }

    private static String resolveConstraint(ConstraintViolation<?> violation) {
        if (violation.getConstraintDescriptor() == null || violation.getConstraintDescriptor().getAnnotation() == null) {
            return "";
        }
        return violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
    }

    /**
     * record DTO 의 컴포넌트 선언 순서를 필드명 -> 순번으로 만든다. record 가 아니면 빈 맵을 준다.
     * 리플렉션의 필드 순서는 스펙상 보장되지 않으므로, 보장되는 record 컴포넌트 순서만 사용한다.
     */
    private static Map<String, Integer> resolveFieldOrder(Object target) {
        if (target == null || !target.getClass().isRecord()) {
            return Map.of();
        }

        Map<String, Integer> fieldOrder = new LinkedHashMap<>();
        RecordComponent[] components = target.getClass().getRecordComponents();
        for (int index = 0; index < components.length; index++) {
            fieldOrder.put(components[index].getName(), index);
        }
        return fieldOrder;
    }

    private static ValidationErrorItem toItem(String field, String rawMessage, String defaultCode) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return new ValidationErrorItem(defaultCode, field, "요청 값이 올바르지 않습니다.");
        }

        int delimiterIndex = rawMessage.indexOf(CODE_DELIMITER);
        if (delimiterIndex <= 0) {
            return new ValidationErrorItem(defaultCode, field, rawMessage);
        }

        String candidateCode = rawMessage.substring(0, delimiterIndex);
        if (!isErrorCode(candidateCode)) {
            return new ValidationErrorItem(defaultCode, field, rawMessage);
        }
        return new ValidationErrorItem(candidateCode, field, rawMessage.substring(delimiterIndex + 1).trim());
    }

    /**
     * DOMAIN_NNN 형태(대문자/숫자/밑줄)만 코드로 인정한다. 메시지 안의 일반 콜론과 구분하기 위함이다.
     */
    private static boolean isErrorCode(String candidate) {
        return candidate.matches("[A-Z][A-Z0-9_]*_[0-9]{3}");
    }

    private static String resolveLeafField(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        if (path.isBlank()) {
            return UNKNOWN_FIELD;
        }
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }

    private static ResponseEntity<Response<Void>> build(List<RawError> rawErrors, Map<String, Integer> fieldOrder, String defaultCode) {
        if (rawErrors.isEmpty()) {
            return respond(List.of(new ValidationErrorItem(defaultCode, UNKNOWN_FIELD, "요청 값이 올바르지 않습니다.")));
        }

        List<String> appearanceOrder = new ArrayList<>();
        for (RawError rawError : rawErrors) {
            if (!appearanceOrder.contains(rawError.field())) {
                appearanceOrder.add(rawError.field());
            }
        }

        List<RawError> sorted = new ArrayList<>(rawErrors);
        sorted.sort(Comparator
            .comparingInt((RawError rawError) -> fieldIndex(rawError.field(), fieldOrder, appearanceOrder))
            .thenComparingInt(rawError -> CONSTRAINT_PRIORITY.getOrDefault(rawError.constraint(), UNKNOWN_CONSTRAINT_PRIORITY))
            // 같은 필드에 같은 우선순위 제약이 둘 이상이어도 순서가 흔들리지 않도록 메시지로 마무리한다.
            .thenComparing(rawError -> rawError.rawMessage() == null ? "" : rawError.rawMessage()));

        List<ValidationErrorItem> errors = new ArrayList<>();
        for (RawError rawError : sorted) {
            errors.add(toItem(rawError.field(), rawError.rawMessage(), defaultCode));
        }
        return respond(errors);
    }

    private static int fieldIndex(String field, Map<String, Integer> fieldOrder, List<String> appearanceOrder) {
        Integer declaredIndex = fieldOrder.get(field);
        if (declaredIndex != null) {
            return declaredIndex;
        }
        // DTO 선언 순서를 모르는 필드(파라미터 검증 등)는 선언된 필드 뒤에 등장 순서대로 둔다.
        return fieldOrder.size() + Math.max(appearanceOrder.indexOf(field), 0);
    }

    /**
     * 대표 메시지는 {@code resultMessage} 에 <b>문자열로</b>, 필드별 목록은 {@code fieldErrors} 에 싣는다.
     * 두 값을 한 칸에 겹쳐 담지 않는다 — 그러면 클라이언트가 오류 종류마다 타입을 분기해야 한다.
     */
    private static ResponseEntity<Response<Void>> respond(List<ValidationErrorItem> errors) {
        ValidationErrorItem first = errors.getFirst();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Response.fail(first.code(), first.message(), errors));
    }

    /**
     * 정렬 전 원본 오류. 제약 애노테이션 이름을 함께 들고 있어야 우선순위를 매길 수 있다.
     */
    private record RawError(String field, String rawMessage, String constraint) {
    }
}
