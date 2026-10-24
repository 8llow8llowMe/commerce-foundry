package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.config.spi.ConfigurationService;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.tool.schema.spi.DelayedDropRegistryNotAvailableImpl;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * 엔티티에서 Hibernate 가 만드는 PostgreSQL DDL 이 커밋된 스냅숏({@code src/test/resources/ddl/operations-schema.sql})과 같은지 본다
 * ({@code coding-conventions.md} §9-8). 스프링 컨텍스트 · DB 없이 돈다.
 *
 * <p>스냅숏은 두 곳에 쓴다 — PR 본문의 운영 DDL 원본, 그리고 리포지토리 테스트가 PostgreSQL 컨테이너에 적용한 뒤
 * {@code ddl-auto=validate} 로 엔티티와 대조하는 init script. 엔티티를 바꾸고 스냅숏을 갱신하지 않으면 여기서 실패한다.
 *
 * <p>Hibernate 7 에는 {@code SchemaExport} 가 없어 스키마 생성 도구({@link SchemaManagementToolCoordinator})를 스크립트 모드로 부른다.
 * 네이밍 전략은 Spring Boot 기본값과 같게 둔다 — 서비스가 local · dev 에서 {@code update} 로 만드는 스키마와 같은 이름이 나오게.
 * 방언 버전은 운영 PostgreSQL 메이저 버전으로 고정한다 — DB 에 붙지 않으면 Hibernate 는 방언의 최소 지원 버전으로 DDL 을 만든다.
 */
class OperationsSchemaSnapshotTest {

    /** 런타임 엔티티 스캔 범위와 같다 — Boot 는 {@code @SpringBootApplication} 패키지(AutoConfigurationPackages)에서 엔티티를 찾는다. */
    private static final String ENTITY_PACKAGE = "com.commercefoundry";
    private static final Path GENERATED = Path.of("build", "ddl", "operations-schema.sql");
    private static final Path SNAPSHOT = Path.of("src", "test", "resources", "ddl", "operations-schema.sql");
    private static final String SNAPSHOT_RESOURCE = "/ddl/operations-schema.sql";
    private static final int POSTGRESQL_MAJOR_VERSION = 17;
    private static final int POSTGRESQL_IDENTIFIER_MAX_LENGTH = 63;

    private static List<Class<?>> entityClasses;

    @BeforeAll
    static void scanEntities() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        // 스캔 순서는 파일 시스템마다 다르다. 이름순으로 고정해 DDL 순서가 환경에 따라 흔들리지 않게 한다
        List<String> classNames = scanner.findCandidateComponents(ENTITY_PACKAGE).stream()
            .map(BeanDefinition::getBeanClassName)
            .sorted()
            .toList();
        List<Class<?>> classes = new ArrayList<>();
        for (String className : classNames) {
            classes.add(Class.forName(className));
        }
        entityClasses = List.copyOf(classes);
    }

    @Test
    @DisplayName("엔티티에서 만든 DDL 이 커밋된 스냅숏과 같다 (줄 단위 비교)")
    void schemaMatchesSnapshot() throws IOException {
        assertThat(entityClasses).as("스캔된 엔티티").isNotEmpty();

        List<String> generated = lines(generateDdl());
        List<String> snapshot = lines(readSnapshot());

        assertThat(generated)
            .as("엔티티와 DDL 스냅숏이 다르다. %s 를 %s 로 복사하고, 바뀐 부분을 PR 본문 운영 DDL 절에 적는다 (coding-conventions.md §9-8)",
                GENERATED.toAbsolutePath(), SNAPSHOT.toAbsolutePath())
            .containsExactlyElementsOf(snapshot);
    }

    @Test
    @DisplayName("DB 연결 없이 운영과 같은 방언 버전(PostgreSQL 17)으로 DDL 을 만든다")
    void dialectVersionIsPinned() {
        StandardServiceRegistry registry = buildRegistry();
        try {
            Dialect dialect = registry.requireService(JdbcEnvironment.class).getDialect();

            assertThat(dialect).isInstanceOf(PostgreSQLDialect.class);
            assertThat(dialect.getVersion().getDatabaseMajorVersion()).isEqualTo(POSTGRESQL_MAJOR_VERSION);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    @DisplayName("인덱스 · 유니크 키 · check 제약 이름은 idx_ / uk_ / ck_ + 테이블명으로 시작하고 PostgreSQL 식별자 상한 63자 이하다")
    void constraintNamesFollowConvention() {
        SoftAssertions softly = new SoftAssertions();
        for (Class<?> entityClass : entityClasses) {
            Table table = entityClass.getAnnotation(Table.class);
            assertThat(table).as("%s 의 @Table", entityClass.getSimpleName()).isNotNull();

            for (Index index : table.indexes()) {
                assertName(softly, index.name(), "idx_" + table.name() + "_");
            }
            for (UniqueConstraint uniqueConstraint : table.uniqueConstraints()) {
                assertName(softly, uniqueConstraint.name(), "uk_" + table.name() + "_");
            }
            for (CheckConstraint checkConstraint : table.check()) {
                assertName(softly, checkConstraint.name(), "ck_" + table.name() + "_");
            }
        }
        softly.assertAll();
    }

    private static void assertName(SoftAssertions softly, String name, String prefix) {
        softly.assertThat(name).as(name).startsWith(prefix).hasSizeLessThanOrEqualTo(POSTGRESQL_IDENTIFIER_MAX_LENGTH);
    }

    private static String generateDdl() throws IOException {
        Files.createDirectories(GENERATED.getParent());
        Files.deleteIfExists(GENERATED);

        StandardServiceRegistry registry = buildRegistry();
        try {
            MetadataSources sources = new MetadataSources(registry);
            entityClasses.forEach(sources::addAnnotatedClass);
            Metadata metadata = sources.buildMetadata();

            Map<String, Object> settings = registry.requireService(ConfigurationService.class).getSettings();
            SchemaManagementToolCoordinator.process(metadata, registry, settings, DelayedDropRegistryNotAvailableImpl.INSTANCE);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }

        return Files.readString(GENERATED, StandardCharsets.UTF_8);
    }

    private static StandardServiceRegistry buildRegistry() {
        return new StandardServiceRegistryBuilder()
            .applySettings(schemaSettings())
            .build();
    }

    private static Map<String, Object> schemaSettings() {
        return Map.ofEntries(
            Map.entry("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"),
            // DB 에 붙지 않는다 — 방언과 아래 버전 정보만으로 DDL 을 만든다
            Map.entry("hibernate.boot.allow_jdbc_metadata_access", "false"),
            Map.entry("jakarta.persistence.database-product-name", "PostgreSQL"),
            Map.entry("jakarta.persistence.database-major-version", String.valueOf(POSTGRESQL_MAJOR_VERSION)),
            Map.entry("jakarta.persistence.database-minor-version", "0"),
            Map.entry("hibernate.default_schema", "operations"),
            Map.entry("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"),
            Map.entry("hibernate.implicit_naming_strategy", "org.springframework.boot.hibernate.SpringImplicitNamingStrategy"),
            Map.entry("jakarta.persistence.schema-generation.database.action", "none"),
            Map.entry("jakarta.persistence.schema-generation.scripts.action", "create"),
            Map.entry("jakarta.persistence.schema-generation.scripts.create-target", GENERATED.toString()),
            Map.entry("jakarta.persistence.create-database-schemas", "true"),
            // 이전 실행 결과 뒤에 덧붙이지 않는다 (Hibernate 기본은 append)
            Map.entry("hibernate.hbm2ddl.schema-generation.script.append", "false"),
            // 한글 코멘트를 OS 기본 문자셋(Windows CP949)으로 쓰지 않게 한다
            Map.entry("hibernate.hbm2ddl.charset_name", "UTF-8"),
            Map.entry("hibernate.hbm2ddl.delimiter", ";"),
            Map.entry("hibernate.format_sql", "true")
        );
    }

    private static String readSnapshot() throws IOException {
        try (InputStream snapshot = OperationsSchemaSnapshotTest.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
            assertThat(snapshot)
                .as("DDL 스냅숏이 없다. %s 를 %s 로 복사한다", GENERATED.toAbsolutePath(), SNAPSHOT.toAbsolutePath())
                .isNotNull();
            return new String(snapshot.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 줄끝(CRLF · LF) · 들여쓰기 · 빈 줄 차이는 무시한다 — 줄마다 앞뒤 공백을 자르고 빈 줄을 뺀다. 실패하면 AssertJ 가 다른 줄을 보여 준다. */
    private static List<String> lines(String ddl) {
        return ddl.lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
    }
}
