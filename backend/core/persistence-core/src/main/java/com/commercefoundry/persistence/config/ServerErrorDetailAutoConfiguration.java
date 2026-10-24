package com.commercefoundry.persistence.config;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/**
 * DB 를 쓰는 모든 서비스의 커넥션 풀이 pgjdbc 에 {@code logServerErrorDetail=false} 를 넘기게 한다 — 서버 오류 메시지(제약 위반 등)에
 * 행 값(DETAIL)이 실리지 않아 Hibernate 의 WARN 로그에 남지 않는다 ({@code coding-conventions.md} §8, #173 · #279).
 *
 * <p><b>서비스 yml 이 아니라 자동 구성이다.</b> 서비스마다 yml 에 두는 방식은 한 서비스에서 빠져도(#269) 오류 없이 드라이버 기본값(true)으로 돈다.
 * persistence-core 를 의존하는 서비스(곧 DB 를 쓰는 서비스)면 고르지 않고 언제나 걸린다 — 폴백 오류 봉투({@code FallbackErrorAutoConfiguration})와 같은 판단이다.
 * yml 이 {@code true} 로 두어도 {@code false} 로 덮는다 — 켜 둘 이유가 없다. {@code @DataJpaTest} 슬라이스는 이 목록을 읽지 않아
 * {@code AutoConfigureDataJpa.imports} 에도 등록한다 — 리포지토리 테스트(실제 PostgreSQL)가 운영과 같은 드라이버 설정으로 돈다.
 *
 * <ul>
 *   <li><b>시점</b> — 풀이 시작되기 전({@code HikariConfig} 가 봉인되기 전), 빈 초기화 직후에 넣는다. 실제 커넥션은 그 뒤 JPA 가 처음 연다.
 *       {@code new HikariDataSource(HikariConfig)} 처럼 만들자마자 풀을 여는 DataSource 는 봉인돼 기동이 실패한다 — 조용히 빠지지 않는다.</li>
 *   <li><b>감싼 DataSource</b> — 다른 후처리기(프록시 · 트레이싱 데코레이터)보다 먼저 돌고({@link PriorityOrdered}), 감싼 경우도 풀어서 찾는다.</li>
 *   <li><b>URL 파라미터</b> — pgjdbc 는 URL 의 같은 이름 파라미터를 이 속성보다 우선한다. URL 에 이 이름이 있으면 기동을 실패시킨다 —
 *       {@code DB_URL}(Vault 값) 변경 하나로 조용히 무력화되지 않게.</li>
 * </ul>
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
        return new ServerErrorDetailPostProcessor();
    }

    static final class ServerErrorDetailPostProcessor implements BeanPostProcessor, PriorityOrdered {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DataSource dataSource) {
                HikariDataSource hikari = DataSourceUnwrapper.unwrap(dataSource, HikariConfigMXBean.class, HikariDataSource.class);
                if (hikari != null) {
                    apply(hikari, beanName);
                }
            }
            return bean;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        private static void apply(HikariDataSource dataSource, String beanName) {
            String url = dataSource.getJdbcUrl();
            if (url != null && url.contains(LOG_SERVER_ERROR_DETAIL)) {
                // URL 값은 노출하지 않는다 — 비밀번호가 실릴 수 있다
                throw new IllegalStateException("JDBC URL must not set " + LOG_SERVER_ERROR_DETAIL + " — it overrides the forced false "
                    + "(coding-conventions.md §8). dataSource=" + beanName);
            }
            dataSource.addDataSourceProperty(LOG_SERVER_ERROR_DETAIL, "false");
        }
    }
}
