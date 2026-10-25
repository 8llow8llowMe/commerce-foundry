package com.commercefoundry.messaging.config;

import com.commercefoundry.messaging.properties.MessagingProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 메시징 설정을 빈이 만들어지기 전에 검사해, 어긋나면 기동을 멈춘다 ({@code StorageStartupGuard} 와 같은 방식). 위반은 모아서 한 번에 알리고 설정값을 넣지 않는다.
 *
 * <p>{@code NewTopic} 빈은 켜짐과 상관없이 막는다. 꺼져 있으면(기본) 접속 정보가 비어도 뜬다 — 수치 설정만 본다. 켜져 있으면:
 * <ul>
 *   <li>{@code spring.kafka.bootstrap-servers}({@code KAFKA_BOOTSTRAP_SERVERS})가 있다</li>
 *   <li>namespace 형식 · 마지막 토막 = 활성 환경(local · dev · test, 없으면 prod) — {@link MessagingProperties#violations}</li>
 *   <li>{@code spring.kafka.admin.auto-create=false} — 앱은 토픽을 만들지 않는다. KafkaAdmin 은 브로커 자동 생성이 꺼져도 만들기 때문에 앱에서 막는다</li>
 *   <li>모듈이 강제하는 값을 덮지 않았다 — {@code spring.kafka.producer.acks} 는 {@code all}, {@code spring.kafka.consumer.enable-auto-commit} 은 false</li>
 * </ul>
 */
public final class MessagingStartupGuard implements BeanFactoryPostProcessor {

    private static final List<String> ENVIRONMENTS = List.of("local", "dev", "test");
    private static final Set<String> ALL_ACKS = Set.of("all", "-1");

    private final Environment environment;

    public MessagingStartupGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> violations = new ArrayList<>(check());
        if (beanFactory != null && (beanFactory.getBeanNamesForType(NewTopic.class, true, false).length > 0
            || beanFactory.getBeanNamesForType(KafkaAdmin.NewTopics.class, true, false).length > 0)) {
            // 꺼져 있어도 KafkaAdmin(auto-create 기본 true)이 기동 때 만들려 든다 — 켜짐과 상관없이 막는다
            violations.add("NewTopic 빈을 두지 않습니다 — 토픽은 Infra 스크립트가 만든다");
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Kafka 메시징 설정이 기동 조건에 맞지 않습니다 (core/messaging-core/README.md)\n- " + String.join("\n- ", violations));
        }
    }

    List<String> check() {
        Binder binder = Binder.get(environment);
        MessagingProperties properties;
        try {
            properties = binder.bind(MessagingProperties.PREFIX, MessagingProperties.class)
                .orElseGet(() -> new MessagingProperties(false, null, null, null, 65536, null, null, null));
        } catch (BindException exception) {
            // 원인 메시지에 설정값이 들어 있어 잇지 않는다
            return List.of(MessagingProperties.PREFIX + ".* 의 형식이 틀렸습니다(시간은 1s · 500ms · 7d, 수치는 정수) — " + exception.getName());
        }
        List<String> violations = new ArrayList<>(properties.violations(expectedEnvironment(environment)));
        if (!properties.enabled()) {
            return violations;
        }
        // 쉼표 문자열 · YAML 목록 둘 다 목록으로 읽는다
        List<String> servers = binder.bind("spring.kafka.bootstrap-servers", Bindable.listOf(String.class)).orElse(List.of());
        if (servers.isEmpty() || servers.stream().anyMatch(server -> server == null || server.isBlank() || server.contains("${"))) {
            violations.add("spring.kafka.bootstrap-servers(KAFKA_BOOTSTRAP_SERVERS) 가 비어 있습니다");
        }
        if (!"false".equalsIgnoreCase(text(binder, "spring.kafka.admin.auto-create"))) {
            violations.add("spring.kafka.admin.auto-create 를 false 로 두어야 합니다 — 토픽은 Infra 스크립트가 만든다");
        }
        String acks = text(binder, "spring.kafka.producer.acks");
        if (acks != null && !ALL_ACKS.contains(acks.strip().toLowerCase())) {
            violations.add("spring.kafka.producer.acks 를 all 외의 값으로 덮었습니다 — 이벤트 유실을 막으려 all 만 허용합니다");
        }
        if ("true".equalsIgnoreCase(text(binder, "spring.kafka.consumer.enable-auto-commit"))) {
            violations.add("spring.kafka.consumer.enable-auto-commit 을 true 로 덮었습니다 — 처리 커밋 뒤에만 오프셋을 커밋합니다");
        }
        return violations;
    }

    /** Binder 로 읽는다 — 풀리지 않은 플레이스홀더를 예외 없이 문자 그대로 받아(값을 내지 않고) 비었다고 판정한다. 목록 값은 쉼표로 이은 문자열이다. */
    private static String text(Binder binder, String property) {
        return binder.bind(property, String.class).orElse(null);
    }

    /** 활성 프로파일 중 local · dev · test 가 있으면 그것, 없으면 prod. */
    static String expectedEnvironment(Environment environment) {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        return ENVIRONMENTS.stream().filter(active::contains).findFirst().orElse("prod");
    }
}
