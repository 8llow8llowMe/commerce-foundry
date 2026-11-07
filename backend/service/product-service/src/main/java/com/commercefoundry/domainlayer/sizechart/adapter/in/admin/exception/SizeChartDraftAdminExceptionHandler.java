package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.sizechart.application.exception.SizeChartException;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.controller.SizeChartDraftAdminController;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request.SizeChartDraftValidationMessage;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportErrorCode;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftTransportException;
import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartDraftUnavailableException;
import feign.FeignException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 초안 API에만 적용한다. #216 오류 계약과 보안 예외 처리의 우선순위는 보존한다. */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RestControllerAdvice(assignableTypes = SizeChartDraftAdminController.class)
public class SizeChartDraftAdminExceptionHandler {
    private static final String INVALID = "SIZE_CHART_DRAFT_100";

    @ExceptionHandler(SizeChartDraftTransportException.class)
    public ResponseEntity<Response<Void>> transport(SizeChartDraftTransportException exception) {
        var code = exception.getErrorCode();
        var response = ResponseEntity.status(code.getHttpStatus()).cacheControl(CacheControl.noStore());
        if (exception instanceof SizeChartDraftUnavailableException unavailable
            && code == SizeChartDraftTransportErrorCode.REGISTRATION_UNAVAILABLE) {
            response.location(URI.create("/api/v1/admin/size-chart-drafts/" + unavailable.getDraftId()));
        }
        return response.body(Response.fail(code.getCode(), code.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> bodyValidation(MethodArgumentNotValidException exception) {
        return noStore(ValidationErrorSupport.toResponse(normalize(exception), INVALID));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> methodValidation(HandlerMethodValidationException exception) {
        if (exception.getBeanResults().isEmpty()) {
            return noStore(ValidationErrorSupport.toResponse(exception, INVALID));
        }
        var body = exception.getBeanResults().getFirst();
        var errors = new BeanPropertyBindingResult(body.getArgument(), body.getObjectName());
        exception.getBeanResults().forEach(result -> result.getAllErrors().forEach(errors::addError));
        exception.getValueResults().forEach(result -> result.getResolvableErrors().forEach(error -> errors.addError(
            new FieldError(body.getObjectName(), result.getMethodParameter().getParameterName(), null, false,
                error.getCodes(), null, error.getDefaultMessage()))));
        return bodyValidation(new MethodArgumentNotValidException(body.getMethodParameter(), errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> malformed(HttpMessageNotReadableException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, INVALID));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, INVALID));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Response<Void>> missingHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
            .body(Response.fail("SIZE_CHART_DRAFT_111", "Idempotency-Key 헤더에 UUID를 입력해 주세요."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> missingParameter(MissingServletRequestParameterException exception) {
        return noStore(ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), INVALID));
    }

    @ExceptionHandler(SizeChartException.class)
    public ResponseEntity<Response<Void>> chart(SizeChartException exception) {
        return businessFailure(exception.getErrorCode().getHttpStatus());
    }

    @ExceptionHandler(ProductException.class)
    public ResponseEntity<Response<Void>> product(ProductException exception) {
        return businessFailure(exception.getErrorCode().getHttpStatus());
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Response<Void>> remoteFailure(FeignException exception) {
        // 방어 경계: 원격 예외의 본문과 메시지를 운영자에게 전달하지 않는다.
        return transport(new SizeChartDraftTransportException(SizeChartDraftTransportErrorCode.RESULT_UNAVAILABLE));
    }

    private ResponseEntity<Response<Void>> businessFailure(HttpStatus status) {
        var code = switch (status) {
            case NOT_FOUND -> SizeChartDraftTransportErrorCode.NOT_FOUND;
            case CONFLICT -> SizeChartDraftTransportErrorCode.BINDING_CONFLICT;
            case SERVICE_UNAVAILABLE -> SizeChartDraftTransportErrorCode.RESULT_UNAVAILABLE;
            default -> SizeChartDraftTransportErrorCode.UNSAFE_INPUT;
        };
        return transport(new SizeChartDraftTransportException(code));
    }

    private MethodArgumentNotValidException normalize(MethodArgumentNotValidException exception) {
        var original = exception.getBindingResult();
        var errors = new BeanPropertyBindingResult(original.getTarget(), original.getObjectName());
        original.getAllErrors().forEach(error -> {
            String message = error.getDefaultMessage();
            if (message != null && message.startsWith("SIZE_CHART_DRAFT_")) {
                errors.addError(error);
            } else {
                String field = error instanceof FieldError fieldError ? fieldError.getField() : "correctedChart";
                errors.addError(new FieldError(original.getObjectName(), field, SizeChartDraftValidationMessage.CHART));
            }
        });
        return new MethodArgumentNotValidException(exception.getParameter(), errors);
    }

    private ResponseEntity<Response<Void>> noStore(ResponseEntity<Response<Void>> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
