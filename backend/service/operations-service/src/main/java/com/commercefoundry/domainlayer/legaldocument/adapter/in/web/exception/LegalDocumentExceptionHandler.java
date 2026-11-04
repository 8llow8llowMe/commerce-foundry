package com.commercefoundry.domainlayer.legaldocument.adapter.in.web.exception;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.exception.ValidationErrorSupport;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentErrorCode;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentException;
import jakarta.validation.ConstraintViolation;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.commercefoundry.domainlayer.legaldocument")
public class LegalDocumentExceptionHandler {

    @ExceptionHandler(LegalDocumentException.class)
    public ResponseEntity<Response<Void>> handleLegalDocumentException(LegalDocumentException exception) {
        LegalDocumentErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus()).cacheControl(org.springframework.http.CacheControl.noStore())
            .body(Response.fail(errorCode.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, LegalDocumentErrorCode.INVALID_REQUEST.getCode()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        if (exception.getBeanResults().isEmpty()) {
            return noStore(ValidationErrorSupport.toResponse(exception, LegalDocumentErrorCode.INVALID_REQUEST.getCode()));
        }
        return noStore(ValidationErrorSupport.toResponse(asBodyValidationException(exception), LegalDocumentErrorCode.INVALID_REQUEST.getCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleNotReadable(HttpMessageNotReadableException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, LegalDocumentErrorCode.INVALID_REQUEST.getCode()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Response<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return noStore(ValidationErrorSupport.toResponse(exception, LegalDocumentErrorCode.PARAMETER_TYPE_INVALID.getCode()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Response<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
        return noStore(ValidationErrorSupport.toMissingParameterResponse(exception.getParameterName(), LegalDocumentErrorCode.PARAMETER_REQUIRED.getCode()));
    }

    private static MethodArgumentNotValidException asBodyValidationException(HandlerMethodValidationException exception) {
        ParameterErrors body = exception.getBeanResults().getFirst();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(body.getArgument(), body.getObjectName());
        exception.getBeanResults().forEach(result -> result.getAllErrors().forEach(bindingResult::addError));
        exception.getValueResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> {
                FieldError fieldError = new FieldError(
                    body.getObjectName(), field == null ? "request" : field, result.getArgument(), false,
                    error.getCodes(), error.getArguments(), error.getDefaultMessage()
                );
                wrapOriginalViolation(result, error, fieldError);
                bindingResult.addError(fieldError);
            });
        });
        return new MethodArgumentNotValidException(body.getMethodParameter(), bindingResult);
    }

    private static void wrapOriginalViolation(ParameterValidationResult result, MessageSourceResolvable error, FieldError fieldError) {
        if (error instanceof ObjectError objectError) {
            if (objectError.contains(ConstraintViolation.class)) {
                fieldError.wrap(objectError.unwrap(ConstraintViolation.class));
            }
            return;
        }
        try {
            fieldError.wrap(result.unwrap(error, ConstraintViolation.class));
        } catch (IllegalArgumentException noViolation) {
            // 호출부가 직접 만든 오류다. 제약 이름으로만 자리를 판정한다.
        }
    }
    private ResponseEntity<Response<Void>> noStore(ResponseEntity<Response<Void>> response) {
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
            .cacheControl(org.springframework.http.CacheControl.noStore()).body(response.getBody());
    }
}
