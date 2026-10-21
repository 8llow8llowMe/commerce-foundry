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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
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
 *   <li>HTTP 는 {@link UrlConnectionHttpClient} 하나이고 연결 · 응답 타임아웃을 설정에서 받는다 ({@code architecture-guide.md} §8).</li>
 *   <li>체크섬 계산 · 검증은 {@code WHEN_REQUIRED} 로 낮춘다. SDK 2.30 부터 기본값이 모든 업로드에 CRC 체크섬 헤더 · trailer 를 붙이고
 *       응답 체크섬을 검증하는데, 이를 지원하지 않는 S3 호환 스토리지(R2 · 일부 MinIO · NCP 등)가 400 · 501 로 거절하거나 검증이 실패한다.</li>
 *   <li>SDK 예외는 {@link StorageException} 으로 감싼다. 키 · 서명 URL 은 메시지에 넣지 않는다.</li>
 * </ul>
 */
final class S3CompatibleObjectStorageClient implements ObjectStorageClient, AutoCloseable {

    /** 브라우저(HTTP 클라이언트)가 스스로 넣고 직접 넣을 수 없는 서명 헤더 — {@link PresignedUpload#headers()} 에서 뺀다. */
    private static final Set<String> CLIENT_MANAGED_HEADERS = Set.of("host", "content-length");
    private static final String NO_SUCH_BUCKET = "NoSuchBucket";
    private static final int RANGE_NOT_SATISFIABLE = 416;

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

    static S3CompatibleObjectStorageClient create(StorageProperties properties) {
        URI endpoint = URI.create(require(properties.endpoint(), "storage.endpoint"));
        Region region = Region.of(require(properties.region(), "storage.region"));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(require(properties.accessKey(), "storage.access-key"), require(properties.secretKey(), "storage.secret-key")));
        UrlConnectionHttpClient.Builder http = UrlConnectionHttpClient.builder()
            .connectionTimeout(Objects.requireNonNull(properties.connectTimeout(), "storage.connect-timeout 이 없습니다"))
            .socketTimeout(Objects.requireNonNull(properties.readTimeout(), "storage.read-timeout 이 없습니다"));

        // Expect: 100-continue 를 끈다 — url-connection 클라이언트는 그 요청의 오류 응답 본문을 버려 403 · 404 의 오류 코드를 잃는다(SDK 경고 문구).
        // 대신 실패할 업로드도 본문을 끝까지 보낸다. put 은 이관(#19)의 작은 이미지만 쓰고, 브라우저 업로드는 서명 URL 이라 이 설정과 무관하다
        S3Configuration serviceConfiguration = S3Configuration.builder()
            .pathStyleAccessEnabled(properties.pathStyleAccess())
            .expectContinueEnabled(false)
            .build();
        S3Client s3Client = S3Client.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(serviceConfiguration)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .httpClientBuilder(http)
            .build();
        S3Presigner presigner = S3Presigner.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(serviceConfiguration)
            .build();
        return new S3CompatibleObjectStorageClient(s3Client, presigner, require(properties.bucket(), "storage.bucket"), properties.presignTtl(),
            require(properties.publicBaseUrl(), "storage.public-base-url"));
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
            response = s3Client.headObject(request -> request.bucket(bucket).key(key));
        } catch (SdkServiceException e) {
            // HEAD 404 는 본문이 없어 객체 없음과 버킷 없음을 가리지 못한다. 버킷은 설정이 정하므로 객체 없음으로 본다
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw translate(Operation.HEAD, e);
        } catch (SdkException e) {
            throw translate(Operation.HEAD, e);
        }
        if (response.contentLength() == null) {
            throw new StorageException(Reason.UNAVAILABLE, Operation.HEAD);
        }
        return Optional.of(new StoredObject(response.contentLength(), response.contentType(), response.eTag()));
    }

    @Override
    public byte[] readRange(String key, int length) {
        ObjectKeys.requireValid(key);
        if (length <= 0) {
            throw new IllegalArgumentException("length 는 1 이상이어야 합니다");
        }
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-" + (length - 1)).build();
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
    public void copy(String sourceKey, String targetKey, String contentType, String cacheControl) {
        ObjectKeys.requireValid(sourceKey);
        ObjectKeys.requireValid(targetKey);
        ObjectKeys.requireText(contentType, "contentType");
        CopyObjectRequest request = CopyObjectRequest.builder()
            .sourceBucket(bucket).sourceKey(sourceKey)
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
            s3Client.deleteObject(request -> request.bucket(bucket).key(key));
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
}
