package com.commercefoundry.persistence.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * DB 를 쓰는 모든 서비스의 커넥션 풀이 pgjdbc 에 {@code logServerErrorDetail=false} 를 넘기게 한다 — 서버 오류 메시지(제약 위반 등)에
 * 행 값(DETAIL)이 실리지 않아 Hibernate 의 WARN 로그에 남지 않는다 ({@code coding-conventions.md} §8, #173 · #279).
 *
 * <p><b>서비스 yml 이 아니라 자동 구성이다.</b> 서비스마다 yml 에 두는 방식은 한 서비스에서 빠져도(#269) 오류 없이 드라이버 기본값(true)으로 돈다.
 * persistence-core 를 의존하는 서비스(곧 DB 를 쓰는 서비스)면 고르지 않고 언제나 걸린다 — 폴백 오류 봉투({@code FallbackErrorAutoConfiguration})와 같은 판단이다.
 * yml 이 {@code true} 로 두어도 {@code false} 로 덮는다 — 켜 둘 이유가 없다.
 *
 * <p>풀이 시작되기 전({@code HikariConfig} 가 봉인되기 전), 빈 초기화 직후에 속성을 넣는다. 실제 커넥션은 그 뒤 JPA 가 처음 연다.
 * {@code DB_URL} 의 URL 파라미터는 이 속성보다 이긴다 — URL · 프로파일 yml 에 이 속성을 넣지 않는 규칙은 그대로다 (§8).
 * H2(컨텍스트 테스트)는 모르는 속성이라 무시한다.
 */
@AutoConfiguration
@ConditionalOnClass(HikariDataSource.class)
public class ServerErrorDetailAutoConfiguration {

    /** pgjdbc 속성 이름 그대로다 — 대소문자가 다르거나 오타면 드라이버가 조용히 무시한다. */
    public static final String LOG_SERVER_ERROR_DETAIL = "logServerErrorDetail";

    /** {@code static} — 후처리기는 다른 빈보다 먼저 만들어진다. 인스턴스 메서드면 이 설정 클래스가 일찍 만들어진다는 경고가 난다. */
    @Bean
    static BeanPostProcessor serverErrorDetailDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource dataSource) {
                    dataSource.addDataSourceProperty(LOG_SERVER_ERROR_DETAIL, "false");
                }
                return bean;
            }
        };
    }
}
