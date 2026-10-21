package com.commercefoundry.storage.client;

import com.commercefoundry.storage.client.StorageException.Operation;
import com.commercefoundry.storage.client.StorageException.Reason;
import com.commercefoundry.storage.properties.StorageProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * AWS SDK v2 {@link S3Client} · {@link S3Presigner} 로 S3 호환 스토리지(R2 · S3 · NCP · MinIO)를 부른다. 제공자 차이는 endpoint · region ·
 * path-style 설정으로만 흡수한다.
 *
 * <ul>
 *   <li>HTTP 는 {@link UrlConnectionHttpClient} 하나이고 연결 · 응답 타임아웃과 호출 전체 시간 상한을 설정에서 받는다 ({@code architecture-guide.md} §8).
 *       재시도는 최대 2회 시도다.</li>
 *   <li>체크섬 계산 · 검증은 {@code WHEN_REQUIRED} 로 낮춘다. SDK 2.30 부터 기본값이 모든 업로드에 CRC 체크섬 헤더 · trailer 를 붙이고
 *       응답 체크섬을 검증하는데, 이를 지원하지 않는 S3 호환 스토리지(R2 · 일부 MinIO · NCP 등)가 400 · 501 로 거절하거나 검증이 실패한다.</li>
 *   <li>{@code readRange} · {@code copy} 는 {@code head} 의 ETag 를 조건으로 보낸다(If-Match · x-amz-copy-source-if-match) — 검증과 복사 사이에
 *       같은 키가 바뀌면 412 → {@link Reason#CHANGED}.</li>
 *   <li>SDK 예외는 {@link StorageException} 으로 감싼다. 키 · 서명 URL 은 메시지에 넣지 않는다.</li>
 * </ul>
 */
final class S3CompatibleObjectStorageClient implements ObjectStorageClient, AutoCloseable {

    /** 브라우저(HTTP 클라이언트)가 스스로 넣고 직접 넣을 수 없는 서명 헤더 — {@link PresignedUpload#headers()} 에서 뺀다. */
    private static final Set<String> CLIENT_MANAGED_HEADERS = Set.of("host", "content-length");
    private static final String NO_SUCH_BUCKET = "NoSuchBucket";
    private static final int PRECONDITION_FAILED = 412;
    private static final int RANGE_NOT_SATISFIABLE = 416;
    /** 첫 시도 + 재시도 1번. */
    static final int MAX_ATTEMPTS = 2;

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration presignTtl;
    private final String publicBaseUrl;

    S3CompatibleObjectStorageClient(S3Client s3Client, S3Presigner presigner, String bucket, Duration presignTtl, String publicBaseUrl) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.presignTtl = presignTtl;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * 패키지 밖에서는 {@link ObjectStorageClients#create} 로만 만든다 — 그쪽이 기동 가드와 같은 규칙을 먼저 본다.
     * 이 메서드는 빠진 값만 막는다. 왕복 테스트가 Testcontainers 호스트(루프백이 아닐 수 있다)로 직접 부른다.
     */
    static S3CompatibleObjectStorageClient create(StorageProperties properties) {
        String bucket = require(properties.bucket(), "storage.bucket");
        String publicBaseUrl = require(properties.publicBaseUrl(), "storage.public-base-url");
        Duration presignTtl = requireDuration(properties.presignTtl(), "storage.presign-ttl");
        return new S3CompatibleObjectStorageClient(s3Client(properties), presigner(properties), bucket, presignTtl, publicBaseUrl);
    }

    /** 테스트가 타임아웃 · 재시도 설정이 들어갔는지 {@code serviceClientConfiguration()} 으로 보려고 package-private 이다. */
    static S3Client s3Client(StorageProperties properties) {
        UrlConnectionHttpClient.Builder http = UrlConnectionHttpClient.builder()
            .connectionTimeout(requireDuration(properties.connectTimeout(), "storage.connect-timeout"))
            .socketTimeout(requireDuration(properties.readTimeout(), "storage.read-timeout"));
        Duration apiCallTimeout = requireDuration(properties.apiCallTimeout(), "storage.api-call-timeout");
        return S3Client.builder()
            .endpointOverride(endpoint(properties))
            .region(region(properties))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(serviceConfiguration(properties))
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            // 호출 한 번(재시도 · 백오프 포함)의 전체 시간 상한. connect · read 타임아웃은 시도 하나의 멈춤만 끊어서, SDK 기본 재시도(시도 3~4회)와
            // 백오프가 겹치면 스토리지가 멈췄을 때 호출 한 번이 수십 초 동안 요청 스레드를 잡는다 — 게이트웨이 response-timeout(10s)보다 길다.
            // 재시도는 최대 2회 시도(재시도 1번)로 줄인다. 순간 오류(5xx · 연결 끊김)는 한 번 더 보고, 그래도 안 되면 서비스가 503 으로 돌려준다.
            // 시도별 상한(apiCallAttemptTimeout)은 두지 않는다 — 시도 하나의 멈춤은 connect · read 타임아웃이 끊고 전체는 이 상한이 끊는다.
            // 따로 두면 느리지만 진행 중인 전송(이관 put · 큰 객체 복사)을 잘라 같은 일을 다시 보낸다.
            // getObject 응답 본문 읽기는 이 상한 밖이다 — readRange 는 앞 몇 바이트만 읽고 나머지를 버리므로 read 타임아웃이 끊는다
            .overrideConfiguration(override -> override
                .apiCallTimeout(apiCallTimeout)
                .retryStrategy(retry -> retry.maxAttempts(MAX_ATTEMPTS)))
            .httpClientBuilder(http)
            .build();
    }

    /** 서명은 로컬 계산이라 네트워크 · 타임아웃 설정이 없다. */
    private static S3Presigner presigner(StorageProperties properties) {
        return S3Presigner.builder()
            .endpointOverride(endpoint(properties))
            .region(region(properties))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(serviceConfiguration(properties))
            .build();
    }

    private static URI endpoint(StorageProperties properties) {
        return URI.create(require(properties.endpoint(), "storage.endpoint"));
    }

    private static Region region(StorageProperties properties) {
        return Region.of(require(properties.region(), "storage.region"));
    }

    private static StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(require(properties.accessKey(), "storage.access-key"), require(properties.secretKey(), "storage.secret-key")));
    }

    /**
     * Expect: 100-continue 를 끈다 — url-connection 클라이언트는 그 요청의 오류 응답 본문을 버려 403 · 404 의 오류 코드를 잃는다(SDK 경고 문구).
     * 대신 실패할 업로드도 본문을 끝까지 보낸다. put 은 이관(#19)의 작은 이미지만 쓰고, 브라우저 업로드는 서명 URL 이라 이 설정과 무관하다.
     */
    private static S3Configuration serviceConfiguration(StorageProperties properties) {
        return S3Configuration.builder()
            .pathStyleAccessEnabled(properties.pathStyleAccess())
            .expectContinueEnabled(false)
            .build();
    }

    @Override
    public PresignedUpload presignPut(String key, String contentType, long contentLength) {
        ObjectKeys.requireValid(key);
        ObjectKeys.requireText(contentType, "contentType");
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength 는 1 이상이어야 합니다");
        }
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(contentLength).build();
        try {
            PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(presignTtl)
                .putObjectRequest(put)
                .build());
            return new PresignedUpload(presigned.url().toString(), PresignedUpload.METHOD_PUT, requestHeaders(presigned.signedHeaders()), presigned.expiration());
        } catch (SdkException e) {
            throw translate(Operation.PRESIGN_PUT, e);
        }
    }

    @Override
    public Optional<StoredObject> head(String key) {
        ObjectKeys.requireValid(key);
        HeadObjectResponse response;
        try {
            response = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkServiceException e) {
            // HEAD 404 는 본문이 없어 객체 없음과 버킷 없음을 가리지 못한다. 버킷은 설정이 정하므로 객체 없음으로 본다
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw translate(Operation.HEAD, e);
        } catch (SdkException e) {
            throw translate(Operation.HEAD, e);
        }
        // 크기 · ETag 가 없는 응답은 해석할 수 없다 — ETag 는 이어지는 readRange · copy 의 조건이라 비면 검증 → 복사를 묶지 못한다
        if (response.contentLength() == null || response.eTag() == null || response.eTag().isBlank()) {
            throw new StorageException(Reason.UNAVAILABLE, Operation.HEAD);
        }
        return Optional.of(new StoredObject(response.contentLength(), response.contentType(), response.eTag()));
    }

    @Override
    public byte[] readRange(String key, int length, String expectedETag) {
        ObjectKeys.requireValid(key);
        ObjectKeys.requireText(expectedETag, "expectedETag");
        if (length <= 0) {
            throw new IllegalArgumentException("length 는 1 이상이어야 합니다");
        }
        // If-Match — head 로 확인한 객체가 그 사이 바뀌었으면 412(CHANGED). ETag 는 head 가 준 그대로(따옴표 포함) 보낸다(RFC 9110 entity-tag)
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-" + (length - 1)).ifMatch(expectedETag).build();
        try (ResponseInputStream<GetObjectResponse> in = s3Client.getObject(request)) {
            byte[] head = in.readNBytes(length);
            Long sent = in.response().contentLength();
            if (sent == null || sent > head.length) {
                // Range 를 무시하고 전체를 보내는 서버라도 나머지를 내려받지 않는다
                in.abort();
            }
            return head;
        } catch (SdkServiceException e) {
            // 빈 객체에 Range 를 걸면 416 이다 — 읽을 바이트가 없다
            if (e.statusCode() == RANGE_NOT_SATISFIABLE) {
                return new byte[0];
            }
            throw translate(Operation.READ_RANGE, e);
        } catch (SdkException e) {
            throw translate(Operation.READ_RANGE, e);
        } catch (IOException | UncheckedIOException e) {
            throw new StorageException(Reason.UNAVAILABLE, Operation.READ_RANGE, e);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey, String contentType, String cacheControl, String expectedETag) {
        ObjectKeys.requireValid(sourceKey);
        ObjectKeys.requireValid(targetKey);
        ObjectKeys.requireText(contentType, "contentType");
        ObjectKeys.requireText(expectedETag, "expectedETag");
        // x-amz-copy-source-if-match — 검증한 원본과 복사하는 원본이 같아야 한다. 다르면 412(CHANGED)
        CopyObjectRequest request = CopyObjectRequest.builder()
            .sourceBucket(bucket).sourceKey(sourceKey).copySourceIfMatch(expectedETag)
            .destinationBucket(bucket).destinationKey(targetKey)
            .metadataDirective(MetadataDirective.REPLACE).contentType(contentType).cacheControl(cacheControl)
            .build();
        try {
            s3Client.copyObject(request);
        } catch (SdkException e) {
            throw translate(Operation.COPY, e);
        }
    }

    @Override
    public void delete(String key) {
        ObjectKeys.requireValid(key);
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            // S3 는 없는 키도 204 로 답하지만, 404 NoSuchKey 를 주는 호환 스토리지도 있다 — 지우려던 상태가 이미 됐다
            if (reasonOf(e) == Reason.NOT_FOUND) {
                return;
            }
            throw translate(Operation.DELETE, e);
        }
    }

    @Override
    public void put(String key, byte[] bytes, String contentType, String cacheControl) {
        ObjectKeys.requireValid(key);
        ObjectKeys.requireText(contentType, "contentType");
        Objects.requireNonNull(bytes, "bytes");
        PutObjectRequest request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).cacheControl(cacheControl).build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw translate(Operation.PUT, e);
        }
    }

    @Override
    public String publicUrlOf(String key) {
        return ObjectKeys.publicUrl(publicBaseUrl, key);
    }

    @Override
    public void close() {
        try {
            presigner.close();
        } finally {
            s3Client.close();
        }
    }

    /** 서명한 헤더 가운데 요청에 직접 실어야 하는 것 — Host · Content-Length 는 HTTP 클라이언트가 넣는다. */
    private static Map<String, String> requestHeaders(Map<String, List<String>> signedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        signedHeaders.forEach((name, values) -> {
            if (!CLIENT_MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, String.join(",", values));
            }
        });
        return headers;
    }

    private static StorageException translate(Operation operation, SdkException e) {
        return new StorageException(reasonOf(e), operation, e);
    }

    /** 상태 코드로 원인을 가른다. 응답이 없는 실패(연결 · 타임아웃 · 응답 해석)는 {@code UNAVAILABLE} 이다. */
    static Reason reasonOf(SdkException e) {
        if (!(e instanceof SdkServiceException service)) {
            return Reason.UNAVAILABLE;
        }
        int status = service.statusCode();
        if (status == 401 || status == 403) {
            return Reason.ACCESS_DENIED;
        }
        if (status == 404) {
            return e instanceof NoSuchKeyException || !NO_SUCH_BUCKET.equals(errorCode(service)) ? Reason.NOT_FOUND : Reason.REJECTED;
        }
        // 조건부 요청(If-Match · x-amz-copy-source-if-match)은 readRange · copy 만 보낸다
        if (status == PRECONDITION_FAILED) {
            return Reason.CHANGED;
        }
        if (status == 408 || status == 429 || status >= 500 || status < 400) {
            return Reason.UNAVAILABLE;
        }
        return Reason.REJECTED;
    }

    private static String errorCode(SdkServiceException e) {
        return e instanceof AwsServiceException aws && aws.awsErrorDetails() != null ? aws.awsErrorDetails().errorCode() : null;
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " 가 없습니다. storage.enabled=true 면 StorageStartupGuard 가 먼저 막습니다");
        }
        return value;
    }

    private static Duration requireDuration(Duration value, String property) {
        if (value == null) {
            throw new IllegalStateException(property + " 가 없습니다. storage.enabled=true 면 StorageStartupGuard 가 먼저 막습니다");
        }
        return value;
    }
}
