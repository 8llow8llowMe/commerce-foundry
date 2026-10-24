package com.commercefoundry.domainlayer.shippingfee.application.service;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingPolicyHistoryAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingPolicyAdminUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyHistoryRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.shared.commerce.Money;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 국내 배송비 정책 교체 한 번이 JDBC 로 몇 번 왕복하는지 세는 회귀 테스트 (#255). 도서산간 구간 200개(요청 상한)짜리 정책을 같은 200개로 교체한다 —
 * 구간 삭제 · 새 구간 200 INSERT · 이력 1 INSERT · 이력 구간(교체 전 200 + 후 200) INSERT 가 한 트랜잭션에 나간다.
 *
 * <p>세는 자리는 Hibernate 아래 JDBC 다 — {@code DataSource} 를 datasource-proxy 로 감싸 {@code execute*} · {@code executeBatch} 호출마다 한 번(왕복 1회)을 센다.
 * 배치 묶음은 행이 50개여도 왕복 1회이고, 안에 든 행 수는 따로 센다. Hibernate 통계({@code prepareStatementCount})는 배치 실행 횟수를 따로 내지 않아 쓰지 않는다.
 *
 * <p>H2(PostgreSQL 모드)에서 돈다 — Docker 없이 로컬과 CI 가 같은 결과를 낸다. 배치로 묶을지는 Hibernate 가 정하므로 왕복 수는 엔진과 무관하다
 * (pgjdbc {@code reWriteBatchedInserts} 같은 드라이버 재작성은 켜지 않았다). 같은 흐름의 PostgreSQL 커밋 · 롤백은 {@link ShippingPolicyAdminFacadeTransactionTest} 가 본다.
 *
 * <p>DB 이름을 따로 둔다 — 컨텍스트 로딩 테스트와 같은 인메모리 DB 를 쓰면 {@code create-drop} 이 서로의 테이블을 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:delivery-statement-count;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS delivery")
class ShippingPolicyReplaceStatementCountTest {

    private static final int AREA_COUNT = 200;
    private static final int BATCH_SIZE = 50;
    private static final long ADMIN_MEMBER_ID = 4611686018427387904L;

    @Autowired
    private ShippingPolicyAdminUseCase shippingPolicyAdminUseCase;

    @Autowired
    private JdbcRoundTrips jdbcRoundTrips;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoSpyBean
    private ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;

    @MockitoSpyBean
    private ShippingPolicyHistoryRepositoryPort shippingPolicyHistoryRepositoryPort;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from delivery.shipping_policy_history_area");
        jdbcTemplate.update("delete from delivery.shipping_policy_history");
        jdbcTemplate.update("delete from delivery.shipping_remote_area");
        jdbcTemplate.update("delete from delivery.shipping_policy");
    }

    /** 겹치지 않는 구간 {@code count} 개 — 10번대 우편번호부터 10 간격으로 5칸씩. */
    private static List<RemoteAreaCommand> remoteAreas(int count, long surcharge) {
        return IntStream.range(0, count)
            .mapToObj(index -> new RemoteAreaCommand(
                "구간" + index,
                new PostalCode("%05d".formatted(10_000 + index * 10)),
                new PostalCode("%05d".formatted(10_000 + index * 10 + 5)),
                Money.of(surcharge, KRW)
            ))
            .toList();
    }

    private static ShippingPolicyReplaceCommand command(long baseFee, Long version, List<RemoteAreaCommand> remoteAreas) {
        return new ShippingPolicyReplaceCommand(Money.of(baseFee, KRW), null, remoteAreas, version, ADMIN_MEMBER_ID);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from delivery." + table, Long.class);
    }

    private static int ceilDiv(int rows, int batchSize) {
        return (rows + batchSize - 1) / batchSize;
    }

    @Test
    @DisplayName("Hibernate JDBC 배치가 켜져 있다 — batch_size 50 (키 오타는 조용히 무시되므로 만들어진 SessionFactory 를 본다)")
    void jdbcBatchingIsEnabled() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);

        assertThat(sessionFactory.getSessionFactoryOptions().getJdbcBatchSize()).isEqualTo(BATCH_SIZE);
        assertThat(sessionFactory.getSessionFactoryOptions().isOrderInsertsEnabled()).isTrue();
        assertThat(sessionFactory.getSessionFactoryOptions().isOrderUpdatesEnabled()).isTrue();
    }

    @Test
    @DisplayName("구간 200개 교체 — 구간 삭제는 1문장, INSERT 601행은 배치로 묶여 왕복 13번 이하다")
    void replaceWith200AreasIsBatched() {
        ShippingPolicyAdminResponse created = shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_000, null, remoteAreas(AREA_COUNT, 3_000)));
        jdbcRoundTrips.reset();

        ShippingPolicyAdminResponse replaced = shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_500, created.version(), remoteAreas(AREA_COUNT, 4_000)));

        JdbcRoundTrips.Summary summary = jdbcRoundTrips.summary();
        System.out.println("[#255] 구간 " + AREA_COUNT + "개 교체 1회 — " + summary);

        int insertRows = AREA_COUNT + 1 + AREA_COUNT * 2;
        assertThat(summary.rows("insert")).as("새 구간 200 + 이력 1 + 이력 구간 400").isEqualTo(insertRows);
        assertThat(summary.roundTrips("insert")).as("INSERT 왕복 — 배치 50개씩")
            .isLessThanOrEqualTo(ceilDiv(AREA_COUNT, BATCH_SIZE) + 1 + ceilDiv(AREA_COUNT * 2, BATCH_SIZE));
        assertThat(summary.roundTrips("delete")).as("구간 삭제는 JPQL bulk delete 한 문장").isEqualTo(1);
        assertThat(summary.roundTrips("update")).as("정책 행 조건부 UPDATE 한 문장").isEqualTo(1);
        assertThat(summary.roundTrips("select")).as("현재 정책 · 구간 조회 + 저장 후 다시 읽기").isLessThanOrEqualTo(4);
        assertThat(summary.totalRoundTrips()).isLessThanOrEqualTo(20);

        assertThat(replaced.version()).isEqualTo(created.version() + 1);
        assertThat(replaced.remoteAreas()).hasSize(AREA_COUNT).allSatisfy(area -> assertThat(area.surcharge().amount()).isEqualTo(4_000L));
        assertThat(count("shipping_remote_area")).as("옛 구간이 남거나 다시 flush 되지 않았다").isEqualTo(AREA_COUNT);
        assertThat(jdbcTemplate.queryForObject("select count(*) from delivery.shipping_remote_area where surcharge_amount <> 4000", Long.class)).isZero();
    }

    @Test
    @DisplayName("배치로 나가도 교체 이력이 교체 전 · 후 구간을 모두 남긴다")
    void historyKeepsBeforeAndAfterAreas() {
        ShippingPolicyAdminResponse created = shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_000, null, remoteAreas(AREA_COUNT, 3_000)));
        shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_500, created.version(), remoteAreas(AREA_COUNT, 4_000)));

        ShippingPolicyHistoryAdminItem latest = shippingPolicyAdminUseCase.getDomesticPolicyHistories(0, 1).contents().getFirst();

        assertThat(latest.before().version()).isEqualTo(created.version());
        assertThat(latest.before().remoteAreas()).hasSize(AREA_COUNT).allSatisfy(area -> assertThat(area.surcharge().amount()).isEqualTo(3_000L));
        assertThat(latest.after().remoteAreas()).hasSize(AREA_COUNT).allSatisfy(area -> assertThat(area.surcharge().amount()).isEqualTo(4_000L));
        assertThat(count("shipping_policy_history")).isEqualTo(2L);
        assertThat(count("shipping_policy_history_area")).as("처음 만든 교체 후 200 + 교체 전 200 + 후 200").isEqualTo(AREA_COUNT * 3L);
    }

    /** 배치로 쌓아 둔 쓰기(구간 삭제 · INSERT · 이력)가 모두 내보내진 뒤 실패해도 Facade 트랜잭션이 통째로 되돌린다. */
    @Test
    @DisplayName("이력 저장 뒤 실패하면 배치로 내보낸 구간 · 이력까지 모두 롤백된다")
    void failureAfterBatchedWritesRollsBackEverything() {
        ShippingPolicyAdminResponse created = shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_000, null, remoteAreas(AREA_COUNT, 3_000)));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("history insert failed after flush");
        }).when(shippingPolicyHistoryRepositoryPort).insert(any());

        assertThatThrownBy(() -> shippingPolicyAdminUseCase.replaceDomesticPolicy(command(9_000, created.version(), remoteAreas(AREA_COUNT, 9_000))))
            .isInstanceOf(DataIntegrityViolationException.class);

        ShippingPolicyAdminResponse current = shippingPolicyAdminUseCase.getDomesticPolicy();
        assertThat(current.version()).isEqualTo(created.version());
        assertThat(current.baseFee().amount()).isEqualTo(3_000L);
        assertThat(current.remoteAreas()).extracting(area -> area.remoteAreaId())
            .isEqualTo(created.remoteAreas().stream().map(area -> area.remoteAreaId()).toList());
        assertThat(count("shipping_policy_history")).isEqualTo(1L);
        assertThat(count("shipping_policy_history_area")).isEqualTo(AREA_COUNT);
    }

    /**
     * 버전 확인(조회)과 조건부 UPDATE 사이에 다른 교체가 끼어든 경우 — 조회 직후 같은 트랜잭션 커넥션으로 버전을 올려 재현한다.
     * UPDATE 가 0건이라 006 이고, 구간 삭제 · INSERT · 이력까지 가지 않는다(끼워 넣은 버전 증가도 함께 롤백된다).
     */
    @Test
    @DisplayName("조회와 저장 사이에 버전이 바뀌면 006 이고 구간 · 이력이 그대로다")
    void concurrentVersionChangeIsConflict() {
        ShippingPolicyAdminResponse created = shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_000, null, remoteAreas(AREA_COUNT, 3_000)));
        doAnswer(invocation -> {
            Object current = invocation.callRealMethod();
            jdbcTemplate.update("update delivery.shipping_policy set version = version + 1");
            return current;
        }).when(shippingPolicyRepositoryPort).findDomesticPolicy();

        assertThatThrownBy(() -> shippingPolicyAdminUseCase.replaceDomesticPolicy(command(9_000, created.version(), remoteAreas(AREA_COUNT, 9_000))))
            .isInstanceOfSatisfying(DeliveryException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT));

        assertThat(jdbcTemplate.queryForObject("select version from delivery.shipping_policy", Long.class)).isEqualTo(created.version());
        assertThat(jdbcTemplate.queryForObject("select count(*) from delivery.shipping_remote_area where surcharge_amount = 3000", Long.class))
            .isEqualTo(AREA_COUNT);
        assertThat(count("shipping_remote_area")).isEqualTo(AREA_COUNT);
        assertThat(count("shipping_policy_history")).isEqualTo(1L);
    }

    @Test
    @DisplayName("배치로 나가도 동시 최초 생성(범위 유일 키 위반)은 006 이다 — 제약 이름이 배치 예외 안에서도 읽힌다(H2 기준, PG 는 리포지토리 테스트)")
    void duplicateCreateUnderBatchingIsConflict() {
        shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_000, null, remoteAreas(1, 3_000)));
        // 다른 요청이 정책이 없을 때 조회한 상황 — 조회 결과를 비워 새로 만들게 한다
        doAnswer(invocation -> Optional.empty()).when(shippingPolicyRepositoryPort).findDomesticPolicy();

        assertThatThrownBy(() -> shippingPolicyAdminUseCase.replaceDomesticPolicy(command(3_500, null, remoteAreas(1, 3_000))))
            .isInstanceOfSatisfying(DeliveryException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT));

        assertThat(count("shipping_policy")).isEqualTo(1L);
        assertThat(count("shipping_policy_history")).isEqualTo(1L);
    }

    @TestConfiguration
    static class JdbcRoundTripsConfig {

        @Bean
        JdbcRoundTrips jdbcRoundTrips() {
            return new JdbcRoundTrips();
        }

        /** Hibernate · JdbcTemplate 이 같은 감싼 DataSource 를 쓰게 빈 자체를 바꾼다 — 트랜잭션 커넥션도 하나로 묶인다. */
        @Bean
        static BeanPostProcessor jdbcRoundTripsDataSourceProxy(ObjectProvider<JdbcRoundTrips> jdbcRoundTrips) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
                        return ProxyDataSourceBuilder.create(dataSource).name("statement-count").listener(jdbcRoundTrips.getObject()).build();
                    }
                    return bean;
                }
            };
        }
    }

    /** JDBC 실행 한 번 = 왕복 1회. 배치면 안에 든 행 수를 따로 센다. */
    static final class JdbcRoundTrips implements QueryExecutionListener {

        private final List<Execution> executions = Collections.synchronizedList(new ArrayList<>());

        record Execution(String type, boolean batch, int rows) {
        }

        record Summary(List<Execution> executions) {

            int roundTrips(String type) {
                return (int) executions.stream().filter(execution -> execution.type().equals(type)).count();
            }

            int batchRoundTrips(String type) {
                return (int) executions.stream().filter(execution -> execution.type().equals(type) && execution.batch()).count();
            }

            int rows(String type) {
                return executions.stream().filter(execution -> execution.type().equals(type)).mapToInt(Execution::rows).sum();
            }

            int totalRoundTrips() {
                return executions.size();
            }

            @Override
            public String toString() {
                StringBuilder text = new StringBuilder("왕복 " + totalRoundTrips() + "회");
                for (String type : List.of("select", "insert", "update", "delete", "other")) {
                    text.append(" | ").append(type).append(" 문장 ").append(rows(type)).append(" · 왕복 ").append(roundTrips(type))
                        .append("(배치 ").append(batchRoundTrips(type)).append(")");
                }
                return text.toString();
            }
        }

        void reset() {
            executions.clear();
        }

        Summary summary() {
            synchronized (executions) {
                return new Summary(List.copyOf(executions));
            }
        }

        @Override
        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        }

        @Override
        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
            String sql = queryInfoList.isEmpty() ? "" : queryInfoList.getFirst().getQuery().stripLeading().toLowerCase(Locale.ROOT);
            String type = List.of("select", "insert", "update", "delete").stream().filter(sql::startsWith).findFirst().orElse("other");
            int rows = execInfo.isBatch()
                ? queryInfoList.stream().mapToInt(queryInfo -> Math.max(1, queryInfo.getParametersList().size())).sum()
                : 1;
            executions.add(new Execution(type, execInfo.isBatch(), rows));
        }
    }
}
