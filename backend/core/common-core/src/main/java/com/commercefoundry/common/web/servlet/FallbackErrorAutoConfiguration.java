package com.commercefoundry.common.web.servlet;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서블릿 서비스의 MVC 예외 처리 사슬에 {@link FallbackErrorResolver} 를 넣는다.
 *
 * <p><b>공통 Configurer 중 유일하게 {@code *BeansConfig} 의 {@code @Import} 가 아니라 자동 구성이다</b> ({@code bootstrap-conventions.md}).
 * 빠뜨려도 오류 없이 기동하고 Boot 기본 오류 JSON 만 조용히 나가므로, 서블릿 서비스면 고르지 않고 언제나 걸리게 한다.
 * 리액티브 게이트웨이도 이 모듈을 쓰지만 서블릿 웹 애플리케이션이 아니라 조건에서 빠진다 — 클래스도 읽히지 않는다(조건은 문자열 · 애노테이션만 본다).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
public class FallbackErrorAutoConfiguration {

    @Bean
    public WebMvcConfigurer fallbackErrorWebMvcConfigurer(JsonMapper jsonMapper) {
        FallbackErrorResolver resolver = new FallbackErrorResolver(jsonMapper);
        return new WebMvcConfigurer() {
            /**
             * 기본 사슬은 {@code ExceptionHandlerExceptionResolver} → {@code ResponseStatusExceptionResolver} → {@code DefaultHandlerExceptionResolver} 다.
             * 첫째 바로 뒤에 넣는다 — 뒤의 둘은 404 · 405 · 415 를 봉투 없는 {@code sendError} 로 끝내므로 그 앞이어야 한다.
             */
            @Override
            public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
                int exceptionHandlerIndex = indexOfExceptionHandlerResolver(resolvers);
                resolvers.add(exceptionHandlerIndex + 1, resolver);
            }
        };
    }

    /** 없으면 -1 이라 맨 앞에 들어간다 — {@code @ExceptionHandler} 가 없는 사슬이면 처음부터 폴백이 받는다. */
    static int indexOfExceptionHandlerResolver(List<HandlerExceptionResolver> resolvers) {
        for (int index = 0; index < resolvers.size(); index++) {
            if (resolvers.get(index) instanceof ExceptionHandlerExceptionResolver) {
                return index;
            }
        }
        return -1;
    }
}
