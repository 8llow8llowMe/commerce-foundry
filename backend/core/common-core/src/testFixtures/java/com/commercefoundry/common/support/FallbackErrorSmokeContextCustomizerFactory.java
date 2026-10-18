package com.commercefoundry.common.support;

import java.util.List;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.util.ClassUtils;

/**
 * 서블릿 테스트 컨텍스트에 {@link FallbackErrorSmokeController} 와 그 advice 를 등록한다. 테스트 픽스처의 {@code META-INF/spring.factories} 로 등록돼
 * 픽스처를 받은 모듈의 Spring 테스트(TestContext)마다 돈다 — 실행 애플리케이션에는 끼어들지 않는다.
 *
 * <ul>
 *   <li><b>모든 테스트 클래스에 같은(equals) 커스터마이저를 돌려준다.</b> 커스터마이저는 컨텍스트 캐시 키의 일부라, 서비스의 {@code *ApplicationTests} 와
 *       {@code *FallbackErrorSmokeTest} 가 계속 같은 컨텍스트를 쓴다 (security-core 의 {@code InternalSecuritySmokeContextCustomizerFactory} 와 같은 방식).</li>
 *   <li>서블릿 웹 컨텍스트에만 등록한다.</li>
 * </ul>
 */
public class FallbackErrorSmokeContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new FallbackErrorSmokeContextCustomizer();
    }

    static final class FallbackErrorSmokeContextCustomizer implements ContextCustomizer {

        static final String CONTROLLER_BEAN_NAME = "fallbackErrorSmokeController";
        static final String ADVICE_BEAN_NAME = "fallbackErrorSmokeHandledAdvice";
        private static final String SERVLET_WEB_CONTEXT = "org.springframework.web.context.WebApplicationContext";

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            if (isServletWebContext(context) && context instanceof BeanDefinitionRegistry registry && !registry.containsBeanDefinition(CONTROLLER_BEAN_NAME)) {
                registry.registerBeanDefinition(CONTROLLER_BEAN_NAME, new RootBeanDefinition(FallbackErrorSmokeController.class));
                registry.registerBeanDefinition(ADVICE_BEAN_NAME, new RootBeanDefinition(FallbackErrorSmokeController.HandledAdvice.class));
            }
        }

        private static boolean isServletWebContext(ConfigurableApplicationContext context) {
            ClassLoader classLoader = context.getClassLoader();
            return ClassUtils.isPresent(SERVLET_WEB_CONTEXT, classLoader)
                && ClassUtils.resolveClassName(SERVLET_WEB_CONTEXT, classLoader).isInstance(context);
        }

        /** 상태가 없다 — 같은 타입이면 같다. 컨텍스트 캐시 키가 테스트 클래스마다 갈라지지 않는다. */
        @Override
        public boolean equals(Object other) {
            return other != null && other.getClass() == getClass();
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }
}
