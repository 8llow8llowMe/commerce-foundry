package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.mediaupload.adapter.out.storage.MediaImageStorageAdapter;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageFormat;
import com.commercefoundry.domainlayer.mediaupload.domain.model.MediaImageUploadKey;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.ObjectStorageClients;
import com.commercefoundry.storage.properties.StorageProperties;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
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

/** Actual signed PUT and anonymous products/media GET. Magic fixture is not a fully decodable image. */
@Testcontainers
@EnabledWithDockerOrOnCi
class MediaImageStorageRoundTripTest {
    private static final String ACCESS = "test-access-key";
    private static final String SECRET = "test-secret-key";
    private static final String BUCKET = "media-roundtrip";
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
        DockerImageName.parse("bitnamilegacy/minio@sha256:6dabb4a2088c9a79908de3bc05f4586c23ad2182c8908e7e3acbf61c1467fb20"))
        .withCreateContainerCmdModifier(command -> command.withEntrypoint("minio"))
        .withCommand("server", "/tmp/minio-data", "--address", ":9000")
        .withEnv("MINIO_ROOT_USER", ACCESS).withEnv("MINIO_ROOT_PASSWORD", SECRET).withEnv("MINIO_CI_CD", "on")
        .withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void signedUploadConfirmsIntoPublicProductsNamespaceAndKeepsPrivateSource() throws Exception {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        String publicBase = endpoint + "/" + BUCKET;
        var properties = new StorageProperties(true, endpoint, "us-east-1", BUCKET, ACCESS, SECRET, publicBase, true,
            Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(60));
        ObjectStorageClient real = ObjectStorageClients.create(properties);
        try (S3Client inspector = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET))).forcePathStyle(true)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED).build();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            inspector.createBucket(request -> request.bucket(BUCKET));
            inspector.putBucketPolicy(request -> request.bucket(BUCKET).policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/products/*"]}]}
                """.formatted(BUCKET)));
            ObjectStorageClient storage = spy(real);
            var adapter = new MediaImageStorageAdapter(storage, MediaUrlPolicy.of(publicBase));
            var key = new MediaImageUploadKey(UUID.randomUUID(), MediaImageFormat.PNG);
            byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13};
            var ticket = adapter.issue(7, key, bytes.length);
            var put = HttpRequest.newBuilder(URI.create(ticket.uploadUrl())).timeout(Duration.ofSeconds(30))
                .method(ticket.method(), HttpRequest.BodyPublishers.ofByteArray(bytes));
            ticket.headers().forEach(put::header);
            assertThat(http.send(put.build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            var confirmed = adapter.confirm(7, key);
            assertThat(adapter.confirm(7, key).publicUrl()).isEqualTo(confirmed.publicUrl());
            var publicGet = http.send(HttpRequest.newBuilder(URI.create(confirmed.publicUrl())).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertThat(publicGet.statusCode()).isEqualTo(200);
            assertThat(publicGet.body()).isEqualTo(bytes);
            assertThat(publicGet.headers().firstValue("Content-Type")).contains("image/png");
            assertThat(publicGet.headers().firstValue("Cache-Control")).contains(MediaImageStorageAdapter.CACHE_CONTROL);
            var sourceGet = HttpRequest.newBuilder(URI.create(publicBase + "/" + key.sourceKey(7)))
                .timeout(Duration.ofSeconds(30)).GET().build();
            assertThat(http.send(sourceGet, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            assertThat(storage.head(key.sourceKey(7))).isPresent();
            verify(storage, never()).delete(anyString());
        } finally {
            if (real instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
