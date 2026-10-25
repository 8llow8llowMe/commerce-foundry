package com.commercefoundry.apigateway.filter;

import com.commercefoundry.common.log.CorrelationIds;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * 게이트웨이의 Reactor 스레드가 바뀌어도 상관 id MDC 가 로그에 남게 한다 (#284).
 *
 * <p>서블릿 서비스는 요청 스레드가 하나라 필터의 MDC 만으로 된다. 게이트웨이는 이벤트 루프가 바뀌므로 컨텍스트 전파가 필요하다.
 */
@Configuration
public class CorrelationIdReactorConfiguration {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    public CorrelationIdReactorConfiguration() {
        register();
    }

    static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ContextRegistry.getInstance().registerThreadLocalAccessor(new CorrelationIdThreadLocalAccessor());
            Hooks.enableAutomaticContextPropagation();
        }
    }

    static final class CorrelationIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return CorrelationIds.MDC_KEY;
        }

        @Override
        public String getValue() {
            return MDC.get(CorrelationIds.MDC_KEY);
        }

        @Override
        public void setValue(String value) {
            MDC.put(CorrelationIds.MDC_KEY, value);
        }

        @Override
        public void setValue() {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
