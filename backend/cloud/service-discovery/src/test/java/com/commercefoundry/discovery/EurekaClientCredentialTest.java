package com.commercefoundry.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.commons.util.InetUtilsProperties;
import org.springframework.cloud.netflix.eureka.CloudEurekaClient;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.cloud.netflix.eureka.TimeoutProperties;
import org.springframework.cloud.netflix.eureka.http.DefaultEurekaClientHttpRequestFactorySupplier;
import org.springframework.cloud.netflix.eureka.http.RestClientDiscoveryClientOptionalArgs;
import org.springframework.cloud.netflix.eureka.http.RestClientTransportClientFactories;
import org.springframework.web.client.RestClient;

/**
 * Eureka 클라이언트가 {@code defaultZone} userinfo 를 Basic 으로 싣는지, 연결 실패 로그에 비밀번호가 없는지 고정한다 (#289, {@code security-guide.md} §12-3).
 * 헤더는 테스트가 만들지 않는다. {@link CloudEurekaClient} 가 URL 에서 자격 증명을 읽고, 등록은 {@code should-enforce-registration-at-init} 이 기동 중에 끝낸다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = DiscoverySecurityTest.PASSWORD_PROPERTY)
@Import(EurekaClientCredentialTest.CredentialCapture.class)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(30)
class EurekaClientCredentialTest {

    private static final String APP = "CREDENTIAL-SMOKE";
    private static final String APP_NAME = "credential-smoke";
    private static final String INSTANCE_ID = "credential-smoke";
    /** 형식만 맞춘 더미. 서버 비밀번호({@link DiscoverySecurityTest#PASSWORD})와 다르게 두어 로그 단언이 서버 값에 섞이지 않는다. */
    private static final String CLOSED_PORT_PASSWORD = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String REDIRECTING_LOGGER = "com.netflix.discovery.shared.transport.decorator.RedirectingEurekaHttpClient";
    private static final String REDIRECTING_LEVEL = REDIRECTING_LOGGER + ": ERROR";

    /** 게이트웨이 + 서비스 6개. 연결 실패 로그 가림과 같은 설정이다. */
    private static final List<String> CLIENT_CONFIGS = List.of(
        "cloud/api-gateway/src/main/resources/application.yml",
        "service/auth-service/src/main/resources/application.yml",
        "service/product-service/src/main/resources/application.yml",
        "service/order-service/src/main/resources/application.yml",
        "service/payment-service/src/main/resources/application.yml",
        "service/delivery-service/src/main/resources/application.yml",
        "service/operations-service/src/main/resources/application.yml");

    @LocalServerPort
    private int port;

    @Autowired
    private PeerAwareInstanceRegistry registry;

    @Autowired
    private CredentialCapture capture;

    @BeforeEach
    void cleanBefore() {
        registry.cancel(APP, INSTANCE_ID, false);
        capture.authorizations.clear();
    }

    @AfterEach
    void cleanAfter() {
        registry.cancel(APP, INSTANCE_ID, false);
    }

    @Test
    @DisplayName("7개 모듈은 RedirectingEurekaHttpClient 를 ERROR 로 둔다")
    void clientModulesHideRedirectingLogger() throws Exception {
        Path backend = backendDir();
        for (String config : CLIENT_CONFIGS) {
            String text = Files.readString(backend.resolve(config));
            assertThat(text).as(config).contains(REDIRECTING_LEVEL);
        }
    }

    @Test
    @DisplayName("URL 자격 증명을 Basic 으로 실어 레지스트리에 등록한다")
    void urlUserInfoRegisters() {
        withClient(serviceUrl(DiscoverySecurityTest.PASSWORD), client -> {
            Application application = registry.getApplication(APP);
            assertThat(application).isNotNull();
            assertThat(application.getInstances()).extracting(InstanceInfo::getInstanceId).contains(INSTANCE_ID);
            assertThat(client.getApplicationInfoManager().getInfo().getInstanceId()).isEqualTo(INSTANCE_ID);
            assertThat(capture.authorizations).anyMatch(header -> decodesTo(header, "eureka", DiscoverySecurityTest.PASSWORD));
        });
    }

    @Test
    @DisplayName("틀린 비밀번호는 등록되지 않고 클라이언트가 뜨지 않는다")
    void wrongPasswordDoesNotRegister() {
        String wrong = "wrong-" + DiscoverySecurityTest.PASSWORD;
        assertRegistrationRejected(() -> withClient(serviceUrl(wrong), client -> { }));
        assertThat(capture.authorizations).anyMatch(header -> decodesTo(header, "eureka", wrong));
        assertThat(registry.getApplication(APP)).isNull();
    }

    @Test
    @DisplayName("userinfo 에 콜론이 더 있으면 등록되지 않는다")
    void rawColonInUserInfoDoesNotRegister() {
        String url = "http://eureka:" + DiscoverySecurityTest.PASSWORD + ":extra@127.0.0.1:" + port + "/eureka/";
        assertRegistrationRejected(() -> withClient(url, client -> { }));
        assertThat(registry.getApplication(APP)).isNull();
    }

    @Test
    @DisplayName("연결 거부 로그에 비밀번호가 없다")
    void connectionRefusedLogOmitsPassword(CapturedOutput output) throws Exception {
        int closedPort = closedPort();
        String url = "http://eureka:" + CLOSED_PORT_PASSWORD + "@127.0.0.1:" + closedPort + "/eureka/";
        Throwable thrown = catchThrowable(() -> withClient(url, client -> { }));
        assertThat(thrown).isNotNull();
        assertThat(output).doesNotContain(CLOSED_PORT_PASSWORD);
        assertThat(output).contains("Registration error at startup");
        assertThat(registry.getApplication(APP)).isNull();
    }

    private void withClient(String defaultZone, Consumer<CloudEurekaClient> body) {
        Logger redirecting = (Logger) LoggerFactory.getLogger(REDIRECTING_LOGGER);
        Level previous = redirecting.getLevel();
        redirecting.setLevel(Level.ERROR);
        try (InetUtils inet = new InetUtils(new InetUtilsProperties())) {
            CloudEurekaClient client = open(inet, defaultZone);
            try {
                body.accept(client);
            } finally {
                client.shutdown();
            }
        } finally {
            redirecting.setLevel(previous);
        }
    }

    private CloudEurekaClient open(InetUtils inet, String defaultZone) {
        EurekaInstanceConfigBean instance = new EurekaInstanceConfigBean(inet);
        instance.setInstanceId(INSTANCE_ID);
        instance.setAppname(APP);
        instance.setVirtualHostName(APP_NAME);
        instance.setHostname("127.0.0.1");
        instance.setIpAddress("127.0.0.1");
        instance.setPreferIpAddress(true);
        instance.setNonSecurePort(18081);
        instance.setNonSecurePortEnabled(true);
        instance.setInstanceEnabledOnit(true);
        instance.setLeaseRenewalIntervalInSeconds(60);
        instance.setLeaseExpirationDurationInSeconds(90);

        InstanceInfo info = new EurekaConfigBasedInstanceInfoProvider(instance).get();
        ApplicationInfoManager manager = new ApplicationInfoManager(instance, info);

        EurekaClientConfigBean clientConfig = new EurekaClientConfigBean();
        clientConfig.setRegisterWithEureka(true);
        clientConfig.setFetchRegistry(false);
        clientConfig.setShouldEnforceRegistrationAtInit(true);
        clientConfig.setShouldUnregisterOnShutdown(true);
        clientConfig.setEurekaServerConnectTimeoutSeconds(3);
        clientConfig.setEurekaServerReadTimeoutSeconds(3);
        clientConfig.setServiceUrl(Map.of(EurekaClientConfigBean.DEFAULT_ZONE, defaultZone));

        TimeoutProperties timeouts = new TimeoutProperties();
        timeouts.setConnectTimeout(3000);
        timeouts.setSocketTimeout(3000);
        RestClientTransportClientFactories factories = new RestClientTransportClientFactories(new RestClientDiscoveryClientOptionalArgs(
            new DefaultEurekaClientHttpRequestFactorySupplier(timeouts, Set.of()), RestClient::builder));
        return new CloudEurekaClient(manager, clientConfig, factories, event -> { });
    }

    private String serviceUrl(String password) {
        return "http://eureka:" + password + "@127.0.0.1:" + port + "/eureka/";
    }

    private static void assertRegistrationRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).isNotNull();
    }

    private static boolean decodesTo(String header, String username, String password) {
        if (header == null || !header.startsWith("Basic ")) {
            return false;
        }
        String decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length()).trim()), StandardCharsets.UTF_8);
        return decoded.equals(username + ":" + password);
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static Path backendDir() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            if (isBackend(cursor)) {
                return cursor;
            }
            if (isBackend(cursor.resolve("backend"))) {
                return cursor.resolve("backend");
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("backend root not found from " + Path.of("").toAbsolutePath());
    }

    private static boolean isBackend(Path path) {
        return Files.isDirectory(path.resolve("cloud/service-discovery")) && Files.isDirectory(path.resolve("service/auth-service"));
    }

    /** 클라이언트가 보낸 Authorization 만 남긴다. 보안 필터보다 앞에서 본다. */
    @TestConfiguration
    static class CredentialCapture {

        private final List<String> authorizations = new CopyOnWriteArrayList<>();

        @Bean
        FilterRegistrationBean<Filter> credentialCaptureFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
            registration.setFilter((request, response, chain) -> {
                if (request instanceof HttpServletRequest http && http.getRequestURI().contains("/eureka/apps")) {
                    authorizations.add(http.getHeader("Authorization"));
                }
                chain.doFilter(request, response);
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
