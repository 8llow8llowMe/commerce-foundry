package com.commercefoundry.common.web.servlet;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서블릿 서비스의 오류 응답을 {@code Response.fail} 봉투로 맞춘다. 두 갈래다.
 *
 * <ul>
 *   <li>MVC 예외 처리 사슬에 {@link FallbackErrorResolver} 를 넣는다 — 컨트롤러 · MVC 단계의 오류.</li>
 *   <li>Boot 의 {@code BasicErrorController} 대신 {@link FallbackErrorController} 를 {@code /error} 에 둔다 — 필터 예외 · 방화벽 거부처럼 MVC 밖의 오류.
 *       필터 예외는 가장 바깥의 {@link UnhandledFilterExceptionFilter} 가 받아 {@code sendError(500)} 으로 넘긴다 — Tomcat 이 원래 메시지째 로그하지 않게 (#283).</li>
 * </ul>
 *
 * <p><b>공통 Configurer 중 유일하게 {@code *BeansConfig} 의 {@code @Import} 가 아니라 자동 구성이다</b> ({@code bootstrap-conventions.md}).
 * 빠뜨려도 오류 없이 기동하고 Boot 기본 오류 JSON 만 조용히 나가므로, 서블릿 서비스면 고르지 않고 언제나 걸리게 한다.
 * 리액티브 게이트웨이도 이 모듈을 쓰지만 서블릿 웹 애플리케이션이 아니라 조건에서 빠진다 — 클래스도 읽히지 않는다(조건은 문자열 · 애노테이션만 본다).
 *
 * <p>{@code ErrorMvcAutoConfiguration} <b>앞</b>에 둔다. Boot 의 {@code BasicErrorController} 는 {@code @ConditionalOnMissingBean(ErrorController.class)} 라
 * 우리 컨트롤러가 먼저 정의돼 있어야 빠진다. 뒤에 두면 둘 다 {@code /error} 에 매핑돼 기동이 실패한다.
 * 이름으로 적는다 — 이 모듈은 {@code spring-boot-webmvc} 를 컴파일에만 가진다.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration")
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

    /**
     * {@code /error} 컨트롤러. Boot 의 {@code ErrorController} 타입이 있을 때만 읽는다 — 메서드 시그니처가 그 타입을 쓰므로 바깥 클래스에 두면
     * {@code spring-boot-webmvc} 없이 {@code DispatcherServlet} 만 있는 구성에서 리졸버 등록까지 같이 깨진다.
     *
     * <p>{@code @ConditionalOnMissingBean(ErrorController.class)} 를 걸지 않는다. 서비스가 따로 정의할 일이 없고, 정의하면 조용히 대체되지 않고
     * {@code /error} 매핑 충돌로 기동이 실패하는 쪽이 낫다 — 봉투 규칙이 서비스마다 갈라지는 것을 막는다.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.webmvc.error.ErrorController")
    static class ErrorControllerConfiguration {

        @Bean
        public FallbackErrorController fallbackErrorController(JsonMapper jsonMapper, ObjectProvider<ErrorAttributes> errorAttributes) {
            return new FallbackErrorController(jsonMapper, errorAttributes.getIfAvailable());
        }

        /**
         * 필터 단계 예외를 컨테이너까지 올리지 않고 위 컨트롤러로 넘긴다 (#283). 컨트롤러와 같은 조건에 둔다 — {@code /error} 가 봉투를 쓸 때만
         * {@code sendError(500)} 으로 바꾸는 의미가 있다. Boot 가 {@code Filter} 빈을 {@code getOrder()} 순서로 컨테이너에 등록한다.
         */
        @Bean
        public UnhandledFilterExceptionFilter unhandledFilterExceptionFilter() {
            return new UnhandledFilterExceptionFilter();
        }
    }
}
