# Bootstrap Conventions

## Application

```java
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {
    "com.commercefoundry.domainlayer",
    "com.commercefoundry.global"
})
public class ProductServiceApplication {
}
```

- Feign 이 필요한 서비스만 `@EnableFeignClients` 를 더한다.
- 스케줄러가 있는 서비스만 `@EnableScheduling` 을 더한다 (`global.config.*FeaturesConfig` 에 두는 것을 우선).

## Global Config

서비스 전역 설정은 `global.config` 아래에서 역할별로 나눈다.

| 파일 | 담는 것 |
|------|---------|
| `*BeansConfig` | 공통 Configurer `@Import` (Snowflake, Querydsl, ResourceServerSecurity) |
| `*PropertiesConfig` | 공통 프로퍼티 `@Import` + 서비스 도메인 프로퍼티 `@EnableConfigurationProperties` |
| `*FeaturesConfig` | JPA Auditing, Scheduling, Async 같은 기능 스위치 |
| `*SwaggerConfig` | 서비스 Swagger 그룹 (고객 / 어드민 그룹 분리) |

```java
@Configuration
@Import({
    SwaggerPropertiesConfig.class,
    SnowflakePropertiesConfig.class,
    JwtResourceServerPropertiesConfig.class
})
@EnableConfigurationProperties({StoreProperties.class, TossPaymentsProperties.class})
public class PaymentServicePropertiesConfig {
}
```

## Reason

- 스캔 범위를 `domainlayer`, `global` 로 제한하면 서비스 내부 구조가 명확해진다.
- 공통 설정을 명시적으로 import 하면 어떤 설정이 왜 로딩되는지 바로 보인다.
- `Beans / Properties / Features` 를 나누면 신규 서비스 추가 때 템플릿처럼 재사용하기 쉽다.
