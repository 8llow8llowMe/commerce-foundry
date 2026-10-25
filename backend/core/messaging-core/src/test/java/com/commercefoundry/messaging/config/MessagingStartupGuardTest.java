package com.commercefoundry.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.mock.env.MockEnvironment;

/** 기동 가드 — 꺼짐은 비어도 뜨고, 켜짐은 접속 · namespace · 강제 값을 본다. 위반 메시지에 설정값이 없다. */
class MessagingStartupGuardTest {

    private static MockEnvironment enabled(String profile, String namespace) {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("commerce.messaging.enabled", "true")
            .withProperty("commerce.messaging.namespace", namespace)
            .withProperty("spring.kafka.bootstrap-servers", "kafka-secret-host:9092")
            .withProperty("spring.kafka.admin.auto-create", "false");
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        return environment;
    }

    @Test
    @DisplayName("꺼져 있으면(기본) 접속 정보가 비어도 뜬다")
    void disabledStartsEmpty() {
        assertThat(new MessagingStartupGuard(new MockEnvironment()).check()).isEmpty();
        assertThat(new MessagingStartupGuard(new MockEnvironment().withProperty("commerce.messaging.enabled", "")).check()).isEmpty();
    }

    @Test
    @DisplayName("켜져 있고 namespace 마지막 토막이 활성 환경과 같으면 뜬다 — 프로파일이 없으면 prod")
    void enabledValid() {
        assertThat(new MessagingStartupGuard(enabled("dev", "commerce-foundry.dev")).check()).isEmpty();
        assertThat(new MessagingStartupGuard(enabled(null, "commerce-foundry.prod")).check()).isEmpty();
    }

    @Test
    @DisplayName("namespace 가 다른 환경이면 뜨지 않는다 — dev 클러스터를 prod 설정으로 쓰는 실수를 막는다")
    void namespaceMustMatchEnvironment() {
        assertThat(new MessagingStartupGuard(enabled("dev", "commerce-foundry.prod")).check()).singleElement().asString().contains("활성 환경(dev)");
        assertThat(new MessagingStartupGuard(enabled("dev", "Commerce Foundry")).check()).singleElement().asString().contains("이름 규칙");
    }

    @Test
    @DisplayName("켜져 있으면 접속 주소 · 토픽 자동 생성 끔이 필요하고, acks · 자동 커밋을 덮을 수 없다 — 위반은 한 번에 모두 알리고 값을 내지 않는다")
    void enabledRequirements() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("commerce.messaging.enabled", "true")
            .withProperty("commerce.messaging.namespace", "commerce-foundry.prod")
            .withProperty("spring.kafka.bootstrap-servers", "${KAFKA_BOOTSTRAP_SERVERS}")
            .withProperty("spring.kafka.producer.acks", "1")
            .withProperty("spring.kafka.consumer.enable-auto-commit", "true");

        List<String> violations = new MessagingStartupGuard(environment).check();

        assertThat(violations).hasSize(4);
        assertThat(String.join("\n", violations)).contains("bootstrap-servers", "auto-create", "acks", "enable-auto-commit");
        assertThatThrownBy(() -> new MessagingStartupGuard(environment).postProcessBeanFactory(null))
            .isInstanceOf(IllegalStateException.class)
            .message().doesNotContain("${KAFKA_BOOTSTRAP_SERVERS}");
    }

    @Test
    @DisplayName("접속 주소는 YAML 목록으로 써도 읽는다")
    void bootstrapServersAsList() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("commerce.messaging.enabled", "true")
            .withProperty("commerce.messaging.namespace", "commerce-foundry.dev")
            .withProperty("spring.kafka.bootstrap-servers[0]", "kafka-a:9092")
            .withProperty("spring.kafka.admin.auto-create", "false");
        environment.setActiveProfiles("dev");

        assertThat(new MessagingStartupGuard(environment).check()).isEmpty();
    }

    @Test
    @DisplayName("NewTopic 빈은 꺼져 있어도 기동 실패다 — 앱은 토픽을 만들지 않는다")
    void rejectsNewTopicBeans() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("sampleTopic", new RootBeanDefinition(NewTopic.class, () -> new NewTopic("sample", 1, (short) 1)));

        assertThatThrownBy(() -> new MessagingStartupGuard(new MockEnvironment()).postProcessBeanFactory(beanFactory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NewTopic");
    }

    @Test
    @DisplayName("lock-at-most-for 는 publish-timeout 의 두 배 이상이어야 한다 — 한 주기가 락을 넘기지 않게")
    void lockMustOutlastPublishTimeout() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("commerce.messaging.outbox.relay.publish-timeout", "10s")
            .withProperty("commerce.messaging.outbox.relay.lock-at-most-for", "10s");

        assertThat(new MessagingStartupGuard(environment).check()).singleElement().asString().contains("lock-at-most-for");
    }

    @Test
    @DisplayName("수치는 꺼져 있어도 본다 — 0 이하 · 형식이 틀린 시간은 기동 실패이고 값은 내지 않는다")
    void numbersAlwaysChecked() {
        assertThat(new MessagingStartupGuard(new MockEnvironment().withProperty("commerce.messaging.outbox.relay.batch-size", "0")).check())
            .singleElement().asString().contains("outbox.relay.batch-size");
        assertThat(new MessagingStartupGuard(new MockEnvironment().withProperty("commerce.messaging.outbox.relay.interval", "secret-ish")).check())
            .singleElement().asString().contains("형식").doesNotContain("secret-ish");
    }
}
