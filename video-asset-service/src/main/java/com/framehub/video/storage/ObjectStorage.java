package com.framehub.video.storage;

import com.framehub.video.common.ApiException;
import com.framehub.video.config.VideoProperties;
import com.framehub.video.media.ProcessingBudget;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.*;
import software.amazon.awssdk.services.s3.presigner.model.*;

@Component
public class ObjectStorage implements AutoCloseable {
  private final S3Client s3;
  private final S3Presigner signer;
  private final VideoProperties p;

  public record SignedPart(
      String uploadUrl, Map<String, List<String>> requiredHeaders, long expiresIn) {}

  public ObjectStorage(VideoProperties p) {
    this.p = p;
    var credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
    var config =
        S3Configuration.builder()
            .pathStyleAccessEnabled(true)
            .chunkedEncodingEnabled(false)
            .build();
    s3 =
        S3Client.builder()
            .endpointOverride(URI.create(p.endpoint()))
            .credentialsProvider(credentials)
            .region(Region.US_EAST_1)
            .serviceConfiguration(config)
            .httpClientBuilder(
                ApacheHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(5))
                    .socketTimeout(Duration.ofSeconds(30))
                    .maxConnections(32))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(p.ioTimeout())
                        .apiCallAttemptTimeout(p.ioTimeout())
                        .retryPolicy(b -> b.numRetries(1)))
            .build();
    signer =
        S3Presigner.builder()
            .endpointOverride(URI.create(p.publicEndpoint()))
            .credentialsProvider(credentials)
            .region(Region.US_EAST_1)
            .serviceConfiguration(config)
            .build();
  }

  public String create(String key, String type, String session) {
    return s3.createMultipartUpload(
            b ->
                b.bucket(p.bucket())
                    .key(key)
                    .contentType(type)
                    .metadata(Map.of("session-id", session)))
        .uploadId();
  }

  public SignedPart presign(String key, String uploadId, int part, long expected, Duration ttl) {
    var req =
        signer.presignUploadPart(
            UploadPartPresignRequest.builder()
                .signatureDuration(ttl)
                .uploadPartRequest(
                    UploadPartRequest.builder()
                        .bucket(p.bucket())
                        .key(key)
                        .uploadId(uploadId)
                        .partNumber(part)
                        .contentLength(expected)
                        .build())
                .build());
    return new SignedPart(req.url().toString(), req.signedHeaders(), ttl.toSeconds());
  }

  public List<Part> parts(String key, String uploadId) {
    List<Part> result = new ArrayList<>();
    Integer marker = null;
    do {
      var response =
          s3.listParts(
              ListPartsRequest.builder()
                  .bucket(p.bucket())
                  .key(key)
                  .uploadId(uploadId)
                  .partNumberMarker(marker)
                  .build());
      result.addAll(response.parts());
      if (!Boolean.TRUE.equals(response.isTruncated())) break;
      marker = response.nextPartNumberMarker();
    } while (true);
    return result;
  }

  public void complete(String key, String uploadId, List<Part> parts) {
    s3.completeMultipartUpload(
        b ->
            b.bucket(p.bucket())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(
                    CompletedMultipartUpload.builder()
                        .parts(
                            parts.stream()
                                .map(
                                    x ->
                                        CompletedPart.builder()
                                            .partNumber(x.partNumber())
                                            .eTag(x.eTag())
                                            .build())
                                .toList())
                        .build()));
  }

  public HeadObjectResponse head(String key) {
    ProcessingBudget.check();
    try {
      return s3.headObject(b -> b.bucket(p.bucket()).key(key));
    } catch (S3Exception e) {
      if (e.statusCode() == 404) return null;
      throw e;
    }
  }

  public void abort(String key, String uploadId) {
    if (uploadId == null) return;
    try {
      s3.abortMultipartUpload(b -> b.bucket(p.bucket()).key(key).uploadId(uploadId));
    } catch (S3Exception e) {
      if (!"NoSuchUpload".equals(e.awsErrorDetails().errorCode())) throw e;
    }
  }

  public void delete(String key) {
    s3.deleteObject(b -> b.bucket(p.bucket()).key(key));
  }

  public void download(String key, Path destination, long max, Instant deadline)
      throws IOException {
    try (ResponseInputStream<GetObjectResponse> in =
            s3.getObject(b -> b.bucket(p.bucket()).key(key));
        OutputStream out = Files.newOutputStream(destination)) {
      if (in.response().contentLength() > max) throw new ApiException(400, "源文件超过限制");
      byte[] buffer = new byte[65536];
      long total = 0;
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted() || Instant.now().isAfter(deadline))
          throw new IOException("Download deadline exceeded");
        total += n;
        if (total > max) throw new ApiException(400, "源文件超过限制");
        out.write(buffer, 0, n);
      }
    }
  }

  public void upload(String key, Path file, String type) {
    s3.putObject(b -> b.bucket(p.bucket()).key(key).contentType(type), RequestBody.fromFile(file));
  }

  public void uploadChecked(String key, Path file, String sha) {
    ProcessingBudget.check();
    s3.putObject(
        b -> b.bucket(p.bucket()).key(key).metadata(Map.of("sha256", sha)),
        RequestBody.fromFile(file));
  }

  public void text(String key, String text) {
    ProcessingBudget.check();
    s3.putObject(
        b -> b.bucket(p.bucket()).key(key).contentType("application/vnd.apple.mpegurl"),
        RequestBody.fromString(text));
  }

  public String read(String key) {
    ProcessingBudget.check();
    try (var in = s3.getObject(b -> b.bucket(p.bucket()).key(key))) {
      byte[] bytes = in.readNBytes(1024 * 1024 + 1);
      if (bytes.length > 1024 * 1024) throw new IllegalStateException("Playlist too large");
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public String signedGet(String key) {
    return signedGet(key, p.presignTtl());
  }

  public String signedGet(String key, java.time.Duration ttl) {
    return signer
        .presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(b -> b.bucket(p.bucket()).key(key))
                .build())
        .url()
        .toString();
  }

  public List<S3Object> objects(String prefix) {
    List<S3Object> result = new ArrayList<>();
    for (var page : s3.listObjectsV2Paginator(b -> b.bucket(p.bucket()).prefix(prefix)))
      result.addAll(page.contents());
    return result;
  }

  public List<MultipartUpload> multiparts() {
    List<MultipartUpload> result = new ArrayList<>();
    for (var page : s3.listMultipartUploadsPaginator(b -> b.bucket(p.bucket()).prefix("sources/")))
      result.addAll(page.uploads());
    return result;
  }

  public void deletePrefix(String prefix) {
    for (var object : objects(prefix)) delete(object.key());
  }

  public void close() {
    signer.close();
    s3.close();
  }
}
