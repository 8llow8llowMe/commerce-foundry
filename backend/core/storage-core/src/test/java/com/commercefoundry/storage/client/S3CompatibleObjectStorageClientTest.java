package com.commercefoundry.storage.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.storage.client.StorageException.Reason;
import com.commercefoundry.storage.properties.StorageProperties;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.Abortable;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 네트워크 없이 도는 부분 — 업로드 서명, 공개 URL, 키 검사, SDK 설정 · 예외 분류, 그리고 실제 서버로는 만들기 어려운 응답 분기(가짜 SDK 클라이언트).
 * 실제 스토리지 왕복은 {@code S3CompatibleObjectStorageRoundTripTest}.
 */
class S3CompatibleObjectStorageClientTest {

    private static final String ENDPOINT = "http://localhost:9000";
    private static final String BUCKET = "test-bucket";
    private static final String KEY = "uploads/2f1c-test.jpg";
    private static final String ETAG = "\"0123456789abcdef0123456789abcdef\"";

    @Test
    @DisplayName("업로드 서명은 오프라인으로 만들고, Content-Type · Content-Length · Host 를 서명하며 만료는 설정 TTL 이다")
    void presignsPutWithContentTypeAndLength() {
        try (S3CompatibleObjectStorageClient client = client(true, Duration.ofMinutes(5))) {
            Instant before = Instant.now();

            PresignedUpload upload = client.presignPut(KEY, "image/jpeg", 2048);

            URI url = URI.create(upload.uploadUrl());
            Map<String, String> query = query(url);
            assertThat(url.getScheme() + "://" + url.getAuthority()).isEqualTo(ENDPOINT);
            assertThat(url.getPath()).isEqualTo("/" + BUCKET + "/" + KEY);
            assertThat(query.get("X-Amz-Algorithm")).isEqualTo("AWS4-HMAC-SHA256");
            assertThat(query.get("X-Amz-Credential")).startsWith("test-access-key/").contains("/us-east-1/s3/aws4_request");
            assertThat(query.get("X-Amz-SignedHeaders").split(";")).contains("content-length", "content-type", "host");
            assertThat(query.get("X-Amz-Expires")).isEqualTo("300");
            assertThat(query).containsKey("X-Amz-Signature");
            // WHEN_REQUIRED — 브라우저가 계산할 수 없는 체크섬을 서명에 넣지 않는다
            assertThat(query.keySet()).noneMatch(name -> name.toLowerCase().contains("checksum"));
            assertThat(query.get("X-Amz-SignedHeaders")).doesNotContain("checksum");

            assertThat(upload.method()).isEqualTo("PUT");
            assertThat(upload.headers()).hasSize(1);
            assertThat(upload.headers()).hasEntrySatisfying(contentTypeHeader(upload), value -> assertThat(value).isEqualTo("image/jpeg"));
            assertThat(upload.expiresAt()).isCloseTo(before.plus(Duration.ofMinutes(5)), within(5, ChronoUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("X-Amz-Expires 는 설정한 TTL(초)이다")
    void expiresAfterConfiguredTtl() {
        try (S3CompatibleObjectStorageClient client = client(true, Duration.ofMinutes(15))) {
            assertThat(query(URI.create(client.presignPut(KEY, "image/png", 1).uploadUrl())).get("X-Amz-Expires")).isEqualTo("900");
        }
    }

    @Test
    @DisplayName("path-style 을 끄면 버킷이 호스트로 간다(virtual-hosted)")
    void usesVirtualHostedStyleWhenPathStyleDisabled() {
        try (S3CompatibleObjectStorageClient client = client(false, Duration.ofMinutes(5))) {
            URI url = URI.create(client.presignPut(KEY, "image/webp", 10).uploadUrl());

            assertThat(url.getHost()).isEqualTo(BUCKET + ".localhost");
            assertThat(url.getPath()).isEqualTo("/" + KEY);
        }
    }

    @Test
    @DisplayName("PresignedUpload 를 로그에 찍어도 서명(쿼리)은 나오지 않는다")
    void hidesSignatureInToString() {
        try (S3CompatibleObjectStorageClient client = client(true, Duration.ofMinutes(5))) {
            PresignedUpload upload = client.presignPut(KEY, "image/jpeg", 2048);

            assertThat(upload.toString()).contains("/" + BUCKET + "/" + KEY).doesNotContain("X-Amz-Signature", "X-Amz-Credential", "test-access-key");
        }
    }

    @Test
    @DisplayName("길이 0 이하 · 빈 Content-Type 은 서명하지 않는다 — 호출한 코드의 버그다")
    void rejectsInvalidPresignArguments() {
        try (S3CompatibleObjectStorageClient client = client(true, Duration.ofMinutes(5))) {
            assertThatThrownBy(() -> client.presignPut(KEY, "image/jpeg", 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.presignPut(KEY, " ", 10)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("공개 URL 은 끝 '/' 를 정규화한 주소 뒤에 키를 '/' 하나로 잇는다. I/O 가 없다")
    void buildsPublicUrlWithNormalizedBase() {
        try (S3CompatibleObjectStorageClient client = client(true, Duration.ofMinutes(5), "https://cdn.example.test/images///")) {
            assertThat(client.publicUrlOf("products/1/2.jpg")).isEqualTo("https://cdn.example.test/images/products/1/2.jpg");
        }
    }

    @Test
    @DisplayName("공개 URL 은 키의 구간마다 퍼센트 인코딩한다 — 공백 · 한글 · '+' 는 바꾸고 '/' · unreserved 는 둔다")
    void encodesKeyInPublicUrl() {
        try (S3CompatibleObjectStorageClient client = client(true, Duration.ofMinutes(5))) {
            assertThat(client.publicUrlOf("products/1/상품 이미지+1.jpg"))
                .isEqualTo("https://cdn.example.test/products/1/%EC%83%81%ED%92%88%20%EC%9D%B4%EB%AF%B8%EC%A7%80%2B1.jpg");
            assertThat(client.publicUrlOf("a-b_c.d~e/f")).isEqualTo("https://cdn.example.test/a-b_c.d~e/f");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/uploads/a.jpg", "uploads//a.jpg", "uploads/", "uploads/../products/a.jpg", "./a.jpg", "uploads/a\n.jpg"})
    @DisplayName("빈 키 · '/' 로 시작 · 빈 구간 · '.' · '..' · 제어 문자는 받지 않는다")
    void rejectsInvalidKeys(String key) {
        try (S3CompatibleObjectStorageClient client = client(true, Duration.ofMinutes(5))) {
            assertThatThrownBy(() -> client.publicUrlOf(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.presignPut(key, "image/jpeg", 10)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("1024바이트를 넘는 키는 받지 않는다 — 한글은 글자당 3바이트로 센다")
    void rejectsTooLongKey() {
        assertThat(ObjectKeys.requireValid("가".repeat(341))).hasSize(341);
        assertThatThrownBy(() -> ObjectKeys.requireValid("가".repeat(342))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SDK 호출 한 번의 전체 시간 상한(api-call-timeout)과 최대 시도 횟수(2)가 S3Client 에 들어간다")
    void appliesCallTimeoutAndRetryLimit() {
        try (S3Client s3Client = S3CompatibleObjectStorageClient.s3Client(properties(true, Duration.ofMinutes(5), "https://cdn.example.test"))) {
            ClientOverrideConfiguration override = s3Client.serviceClientConfiguration().overrideConfiguration();

            assertThat(override.apiCallTimeout()).hasValue(Duration.ofSeconds(8));
            assertThat(override.apiCallAttemptTimeout()).isEmpty();
            assertThat(override.retryStrategy()).hasValueSatisfying(strategy -> assertThat(strategy.maxAttempts()).isEqualTo(2));
        }
    }

    static Stream<Arguments> sdkFailures() {
        return Stream.of(
            Arguments.of("401", s3Exception(401, "Unauthorized"), Reason.ACCESS_DENIED),
            Arguments.of("403 AccessDenied", s3Exception(403, "AccessDenied"), Reason.ACCESS_DENIED),
            Arguments.of("403 SignatureDoesNotMatch", s3Exception(403, "SignatureDoesNotMatch"), Reason.ACCESS_DENIED),
            Arguments.of("404 NoSuchKey(모델 예외)", NoSuchKeyException.builder().statusCode(404).build(), Reason.NOT_FOUND),
            Arguments.of("404 NoSuchKey(오류 코드)", s3Exception(404, "NoSuchKey"), Reason.NOT_FOUND),
            Arguments.of("404 NoSuchBucket — 설정 오류", s3Exception(404, "NoSuchBucket"), Reason.REJECTED),
            Arguments.of("400", s3Exception(400, "InvalidRequest"), Reason.REJECTED),
            Arguments.of("412 PreconditionFailed — 확인 뒤 바뀌었다", s3Exception(412, "PreconditionFailed"), Reason.CHANGED),
            Arguments.of("408", s3Exception(408, "RequestTimeout"), Reason.UNAVAILABLE),
            Arguments.of("429", s3Exception(429, "TooManyRequests"), Reason.UNAVAILABLE),
            Arguments.of("500", s3Exception(500, "InternalError"), Reason.UNAVAILABLE),
            Arguments.of("503 SlowDown", s3Exception(503, "SlowDown"), Reason.UNAVAILABLE),
            Arguments.of("연결 실패 · 타임아웃(응답 없음)", SdkClientException.create("Unable to execute HTTP request"), Reason.UNAVAILABLE));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("sdkFailures")
    @DisplayName("SDK 예외를 상태 코드로 분류한다 — 응답이 없는 실패는 UNAVAILABLE")
    void classifiesSdkFailures(String name, SdkException failure, Reason expected) {
        assertThat(S3CompatibleObjectStorageClient.reasonOf(failure)).isEqualTo(expected);
    }

    @Test
    @DisplayName("StorageException 메시지에는 동작과 원인 구분만 있다 — 키 · 자격 증명 · URL 을 싣지 않는다")
    void exceptionMessageCarriesOnlyOperationAndReason() {
        StorageException exception = new StorageException(Reason.UNAVAILABLE, StorageException.Operation.HEAD, s3Exception(503, "SlowDown"));

        assertThat(exception.reason()).isEqualTo(Reason.UNAVAILABLE);
        assertThat(exception.operation()).isEqualTo(StorageException.Operation.HEAD);
        assertThat(exception.getMessage()).isEqualTo("storage HEAD failed: UNAVAILABLE — " + Reason.UNAVAILABLE.description());
    }

    /** 실제 서버로는 만들기 어려운 응답 분기를 가짜 SDK 클라이언트로 본다 — 요청에 실은 조건(Range · If-Match 등)도 확인한다. */
    @Nested
    class WithFakeSdk {

        private final S3Client s3Client = mock(S3Client.class);
        private final S3Presigner presigner = mock(S3Presigner.class);
        private final S3CompatibleObjectStorageClient client =
            new S3CompatibleObjectStorageClient(s3Client, presigner, BUCKET, Duration.ofMinutes(5), "https://cdn.example.test");

        @Test
        @DisplayName("삭제 — 404 NoSuchKey 를 주는 스토리지여도 성공이다(멱등)")
        void deleteIgnoresNoSuchKey() {
            when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());

            assertThatCode(() -> client.delete(KEY)).doesNotThrowAnyException();
            ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(request.capture());
            assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(request.getValue().key()).isEqualTo(KEY);
        }

        @Test
        @DisplayName("삭제 — 404 NoSuchBucket 은 설정 오류라 REJECTED 다")
        void deleteReportsMissingBucket() {
            when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Exception(404, "NoSuchBucket"));

            assertReason(() -> client.delete(KEY), Reason.REJECTED);
        }

        @Test
        @DisplayName("HEAD — 404(본문 없음)는 비어 있고, 있으면 크기 · 형식 · ETag 를 그대로 돌려준다")
        void headMapsMissingAndPresent() {
            when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build())
                .thenReturn(HeadObjectResponse.builder().contentLength(2048L).contentType("image/jpeg").eTag(ETAG).build());

            assertThat(client.head(KEY)).isEmpty();
            assertThat(client.head(KEY)).hasValue(new StoredObject(2048, "image/jpeg", ETAG));
        }

        @Test
        @DisplayName("HEAD — ETag 가 없는 응답은 해석할 수 없다(UNAVAILABLE). 검증 → 복사를 묶을 수 없다")
        void headRequiresETag() {
            when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(2048L).build());

            assertReason(() -> client.head(KEY), Reason.UNAVAILABLE);
        }

        @Test
        @DisplayName("앞부분 읽기 — Range 와 If-Match(ETag 그대로)를 싣고, 서버가 더 보내면 나머지를 받지 않고 끊는다(abort)")
        void readRangeAbortsWhenServerSendsMore() {
            Abortable abortable = mock(Abortable.class);
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(objectStream(new byte[1000], 1000L, abortable));

            assertThat(client.readRange(KEY, 12, ETAG)).hasSize(12);

            verify(abortable).abort();
            ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(s3Client).getObject(request.capture());
            assertThat(request.getValue().range()).isEqualTo("bytes=0-11");
            assertThat(request.getValue().ifMatch()).isEqualTo(ETAG);
        }

        @Test
        @DisplayName("앞부분 읽기 — 요청한 만큼만 왔으면 끊지 않는다")
        void readRangeKeepsConnectionWhenExact() {
            Abortable abortable = mock(Abortable.class);
            when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(objectStream(new byte[12], 12L, abortable));

            assertThat(client.readRange(KEY, 12, ETAG)).hasSize(12);

            verify(abortable, never()).abort();
        }

        @Test
        @DisplayName("앞부분 읽기 — 빈 객체(Range 416)는 빈 배열, 확인 뒤 바뀐 객체(412)는 CHANGED")
        void readRangeMapsEmptyAndChanged() {
            when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(s3Exception(416, "InvalidRange"))
                .thenThrow(s3Exception(412, "PreconditionFailed"));

            assertThat(client.readRange(KEY, 12, ETAG)).isEmpty();
            assertReason(() -> client.readRange(KEY, 12, ETAG), Reason.CHANGED);
        }

        @Test
        @DisplayName("복사 — 원본 ETag 조건 · 메타데이터 교체를 싣고, 확인 뒤 바뀐 원본(412)은 CHANGED")
        void copySendsSourceETagCondition() {
            when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build())
                .thenThrow(s3Exception(412, "PreconditionFailed"));

            client.copy(KEY, "products/1/2.jpg", "image/jpeg", "public, max-age=31536000, immutable", ETAG);

            ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
            verify(s3Client).copyObject(request.capture());
            assertThat(request.getValue().copySourceIfMatch()).isEqualTo(ETAG);
            assertThat(request.getValue().sourceKey()).isEqualTo(KEY);
            assertThat(request.getValue().destinationKey()).isEqualTo("products/1/2.jpg");
            assertThat(request.getValue().metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
            assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
            assertReason(() -> client.copy(KEY, "products/1/2.jpg", "image/jpeg", null, ETAG), Reason.CHANGED);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", " "})
        @DisplayName("ETag 조건이 비면 스토리지를 부르지 않는다 — 호출한 코드의 버그다")
        void requiresExpectedETag(String eTag) {
            assertThatThrownBy(() -> client.readRange(KEY, 12, eTag)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.copy(KEY, "products/1/2.jpg", "image/jpeg", null, eTag)).isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("close 는 서명기와 S3Client 를 둘 다 닫는다 — 서명기를 닫다 실패해도 S3Client 는 닫는다")
        void closesBothClients() {
            doThrow(new IllegalStateException("presigner close failed")).when(presigner).close();

            assertThatThrownBy(client::close).isInstanceOf(IllegalStateException.class);

            verify(presigner).close();
            verify(s3Client).close();
        }
    }

    private static S3CompatibleObjectStorageClient client(boolean pathStyle, Duration presignTtl) {
        return client(pathStyle, presignTtl, "https://cdn.example.test");
    }

    private static S3CompatibleObjectStorageClient client(boolean pathStyle, Duration presignTtl, String publicBaseUrl) {
        return S3CompatibleObjectStorageClient.create(properties(pathStyle, presignTtl, publicBaseUrl));
    }

    private static StorageProperties properties(boolean pathStyle, Duration presignTtl, String publicBaseUrl) {
        return new StorageProperties(true, ENDPOINT, "us-east-1", BUCKET, "test-access-key", "test-secret-key",
            publicBaseUrl, pathStyle, presignTtl, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(8));
    }

    /** 본문 {@code body} 를 담은 GetObject 응답. 응답 헤더의 Content-Length 는 {@code contentLength} 다. */
    private static ResponseInputStream<GetObjectResponse> objectStream(byte[] body, long contentLength, Abortable abortable) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength(contentLength).build(),
            AbortableInputStream.create(new ByteArrayInputStream(body), abortable));
    }

    private static void assertReason(ThrowingCallable call, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static S3Exception s3Exception(int status, String errorCode) {
        return (S3Exception) S3Exception.builder().statusCode(status).awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build()).build();
    }

    private static String contentTypeHeader(PresignedUpload upload) {
        return upload.headers().keySet().stream().filter(name -> name.equalsIgnoreCase("Content-Type")).findFirst().orElse("Content-Type");
    }

    private static Map<String, String> query(URI url) {
        return Arrays.stream(url.getRawQuery().split("&"))
            .map(pair -> pair.split("=", 2))
            .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }
}
