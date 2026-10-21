package com.commercefoundry.storage.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.storage.client.StorageException.Reason;
import com.commercefoundry.storage.image.ImageFormat;
import com.commercefoundry.storage.properties.StorageProperties;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * 실제 S3 호환 서버(MinIO)와 왕복한다 — 업로드 서명 PUT(HTTP 클라이언트로 실제 업로드) → HEAD → 앞부분 읽기 → 서버 측 복사(메타데이터 교체) → 삭제.
 * 서명 검증 · Range · CopyObject · 조건부 요청(ETag) · 404 처리처럼 오프라인 테스트가 볼 수 없는 것을 본다. Docker 가 없는 로컬은 건너뛰고 CI 에서 돈다.
 *
 * <p>MinIO 공식 이미지({@code minio/minio})는 Docker Hub · quay.io 배포가 끝나 받을 수 없다. Bitnami 가 마지막으로 빌드해 둔
 * {@code bitnamilegacy/minio}(MinIO RELEASE.2025-07-23, 갱신 없음)를 digest 로 고정해 쓰고, Bitnami 설정 스크립트를 거치지 않고 {@code minio server} 를
 * 바로 띄운다 — 기동 중 서버를 한 번 껐다 켜는 설정 단계가 없어 준비 판정이 흔들리지 않는다. 이미지를 바꾸면 이 상수만 고친다.
 *
 * <p>클라이언트는 공개 팩토리({@code ObjectStorageClients.create})가 아니라 package-private {@code create} 로 만든다 — 팩토리는 http 를 루프백
 * 호스트에만 허용하는데, Testcontainers 호스트는 루프백이 아닐 수 있다.
 */
@Testcontainers
@EnabledWithDockerOrOnCi
class S3CompatibleObjectStorageRoundTripTest {

    /**
     * {@code bitnamilegacy/minio:2025.7.23-debian-12-r5} 의 멀티 아키텍처 index digest(linux/amd64 · linux/arm64). 태그는 다시 밀어 넣을 수 있어
     * digest 로 고정한다 — 이미지가 바뀌면 받지 못해 시끄럽게 실패한다.
     */
    private static final DockerImageName MINIO_IMAGE =
        DockerImageName.parse("bitnamilegacy/minio@sha256:6dabb4a2088c9a79908de3bc05f4586c23ad2182c8908e7e3acbf61c1467fb20");
    private static final int S3_PORT = 9000;
    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";
    private static final String BUCKET = "test-bucket";
    private static final String REGION = "us-east-1";
    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    /** 없는 키에 조건으로 싣는 ETag — 객체가 없으면 조건보다 404 가 먼저다. */
    private static final String ANY_ETAG = "\"00000000000000000000000000000000\"";

    /** PNG 시그니처 + IHDR 앞부분. 형식 판별과 바이트 비교에 쓴다. */
    private static final byte[] PNG_BYTES = concat(
        new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'}, new byte[200]);

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(MINIO_IMAGE)
        .withCreateContainerCmdModifier(command -> command.withEntrypoint("minio"))
        .withCommand("server", "/tmp/minio-data", "--address", ":" + S3_PORT)
        .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
        .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
        // 데이터 디렉터리가 컨테이너 루트 디스크에 있어도 쓰게 한다 (CI 용 스위치)
        .withEnv("MINIO_CI_CD", "on")
        .withExposedPorts(S3_PORT)
        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(S3_PORT).withStartupTimeout(Duration.ofMinutes(2)));

    private static S3CompatibleObjectStorageClient client;
    private static S3Client inspector;
    private static HttpClient http;

    @BeforeAll
    static void setUp() {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(S3_PORT);
        client = S3CompatibleObjectStorageClient.create(properties(endpoint, SECRET_KEY));
        // 버킷 생성 · Cache-Control 확인처럼 ObjectStorageClient 가 하지 않는 일만 SDK 로 직접 한다
        inspector = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(REGION))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .forcePathStyle(true)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
        inspector.createBucket(request -> request.bucket(BUCKET));
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
        if (inspector != null) {
            inspector.close();
        }
    }

    @Test
    @DisplayName("서명 PUT 으로 올리고 → HEAD → 앞부분 읽기 → 메타데이터를 바꿔 복사 → 삭제(두 번째도 성공) → HEAD 가 비어 있다")
    void roundTrip() throws Exception {
        String uploadKey = "uploads/" + UUID.randomUUID() + ".png";
        String productKey = "products/1/" + UUID.randomUUID() + ".png";

        PresignedUpload upload = client.presignPut(uploadKey, ImageFormat.PNG.contentType(), PNG_BYTES.length);
        assertThat(send(upload, PNG_BYTES).statusCode()).isEqualTo(200);

        StoredObject uploaded = client.head(uploadKey).orElseThrow();
        assertThat(uploaded.contentLength()).isEqualTo(PNG_BYTES.length);
        assertThat(uploaded.contentType()).isEqualTo("image/png");
        assertThat(uploaded.eTag()).isNotBlank();

        byte[] head = client.readRange(uploadKey, ImageFormat.DETECTION_LENGTH, uploaded.eTag());
        assertThat(head).isEqualTo(Arrays.copyOf(PNG_BYTES, ImageFormat.DETECTION_LENGTH));
        assertThat(ImageFormat.detect(head)).hasValue(ImageFormat.PNG);

        client.copy(uploadKey, productKey, ImageFormat.PNG.contentType(), CACHE_CONTROL, uploaded.eTag());
        HeadObjectResponse copied = inspector.headObject(request -> request.bucket(BUCKET).key(productKey));
        assertThat(copied.contentLength()).isEqualTo(PNG_BYTES.length);
        assertThat(copied.contentType()).isEqualTo("image/png");
        assertThat(copied.cacheControl()).isEqualTo(CACHE_CONTROL);

        client.delete(uploadKey);
        client.delete(uploadKey);
        assertThat(client.head(uploadKey)).isEmpty();
        assertThat(client.head(productKey)).isPresent();

        client.delete(productKey);
        assertThat(client.head(productKey)).isEmpty();
    }

    @Test
    @DisplayName("확인(HEAD) 뒤 같은 키에 다른 내용이 올라오면 옛 ETag 로는 읽기 · 복사가 CHANGED 이고, 새 ETag 로는 복사된다")
    void rejectsObjectChangedAfterHead() {
        String uploadKey = "uploads/" + UUID.randomUUID() + ".png";
        String productKey = "products/1/" + UUID.randomUUID() + ".png";
        client.put(uploadKey, PNG_BYTES, "image/png", null);
        String checkedETag = client.head(uploadKey).orElseThrow().eTag();

        byte[] replaced = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        client.put(uploadKey, replaced, "image/png", null);

        assertReason(() -> client.readRange(uploadKey, ImageFormat.DETECTION_LENGTH, checkedETag), Reason.CHANGED);
        assertReason(() -> client.copy(uploadKey, productKey, "image/png", CACHE_CONTROL, checkedETag), Reason.CHANGED);
        assertThat(client.head(productKey)).isEmpty();

        String currentETag = client.head(uploadKey).orElseThrow().eTag();
        assertThat(currentETag).isNotEqualTo(checkedETag);
        client.copy(uploadKey, productKey, "image/png", CACHE_CONTROL, currentETag);
        assertThat(client.head(productKey)).hasValueSatisfying(object -> assertThat(object.contentLength()).isEqualTo(replaced.length));

        client.delete(uploadKey);
        client.delete(productKey);
    }

    @Test
    @DisplayName("서명과 다른 길이 · 형식으로 올리면 스토리지가 거절하고 객체가 생기지 않는다")
    void rejectsUploadThatDoesNotMatchSignature() throws Exception {
        String key = "uploads/" + UUID.randomUUID() + ".png";
        PresignedUpload upload = client.presignPut(key, "image/png", PNG_BYTES.length);

        assertThat(send(upload, Arrays.copyOf(PNG_BYTES, PNG_BYTES.length - 1)).statusCode()).isEqualTo(403);
        HttpRequest otherType = HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
            .header("Content-Type", "image/svg+xml")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(PNG_BYTES))
            .build();
        assertThat(http.send(otherType, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        assertThat(client.head(key)).isEmpty();
    }

    @Test
    @DisplayName("put 으로 바로 올린 객체도 HEAD · 앞부분 읽기가 된다. 객체보다 길게 읽으면 있는 만큼이다")
    void putsBytesDirectly() {
        String key = "migrated/" + UUID.randomUUID() + ".png";

        client.put(key, PNG_BYTES, "image/png", CACHE_CONTROL);

        StoredObject stored = client.head(key).orElseThrow();
        assertThat(stored.contentLength()).isEqualTo(PNG_BYTES.length);
        assertThat(client.readRange(key, PNG_BYTES.length + 100, stored.eTag())).isEqualTo(PNG_BYTES);
        assertThat(inspector.headObject(request -> request.bucket(BUCKET).key(key)).cacheControl()).isEqualTo(CACHE_CONTROL);
        client.delete(key);
    }

    @Test
    @DisplayName("빈 객체의 앞부분은 빈 배열이다 — Range 416 을 실패로 보지 않는다")
    void readsEmptyObject() {
        String key = "uploads/" + UUID.randomUUID() + ".png";
        client.put(key, new byte[0], "image/png", null);
        String eTag = client.head(key).orElseThrow().eTag();

        assertThat(client.readRange(key, ImageFormat.DETECTION_LENGTH, eTag)).isEmpty();
        client.delete(key);
    }

    @Test
    @DisplayName("없는 키 — HEAD 는 비어 있고, 읽기 · 복사는 NOT_FOUND, 삭제는 성공이다")
    void handlesMissingKey() {
        String missing = "uploads/" + UUID.randomUUID() + ".png";

        assertThat(client.head(missing)).isEmpty();
        assertReason(() -> client.readRange(missing, ImageFormat.DETECTION_LENGTH, ANY_ETAG), Reason.NOT_FOUND);
        assertReason(() -> client.copy(missing, "products/1/" + UUID.randomUUID() + ".png", "image/png", null, ANY_ETAG), Reason.NOT_FOUND);
        client.delete(missing);
    }

    @Test
    @DisplayName("비밀 키가 틀리면 ACCESS_DENIED 다 — 다시 시도해도 풀리지 않는 오류")
    void reportsAccessDeniedWithWrongSecret() {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(S3_PORT);
        try (S3CompatibleObjectStorageClient wrongKey = S3CompatibleObjectStorageClient.create(properties(endpoint, "wrong-secret-key"))) {
            assertReason(() -> wrongKey.head("uploads/" + UUID.randomUUID() + ".png"), Reason.ACCESS_DENIED);
            assertReason(() -> wrongKey.put("uploads/" + UUID.randomUUID() + ".png", PNG_BYTES, "image/png", null), Reason.ACCESS_DENIED);
        }
    }

    /** 브라우저처럼 보낸다 — {@code headers} 를 그대로 싣고, Content-Length 는 HTTP 클라이언트가 본문 길이로 넣는다. */
    private static HttpResponse<Void> send(PresignedUpload upload, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
            .method(upload.method(), HttpRequest.BodyPublishers.ofByteArray(body));
        upload.headers().forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    private static void assertReason(ThrowingCallable call, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static StorageProperties properties(String endpoint, String secretKey) {
        return new StorageProperties(true, endpoint, REGION, BUCKET, ACCESS_KEY, secretKey, "https://cdn.example.test", true,
            Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
