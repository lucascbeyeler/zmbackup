package io.zmbackup.aws;

import io.zmbackup.core.port.StorageProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

public final class S3StorageProvider implements StorageProvider {

    private static final int DELETE_BATCH_SIZE = 1000;

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;

    public S3StorageProvider(String bucket, String region, String keyPrefix, URI endpointOverride) {
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        this.keyPrefix = normalizePrefix(Objects.requireNonNull(keyPrefix, "keyPrefix must not be null"));
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(Objects.requireNonNull(region, "region must not be null")))
                .serviceConfiguration(
                        S3Configuration.builder().checksumValidationEnabled(false).build());
        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride).forcePathStyle(true);
        }
        this.s3Client = builder.build();
    }

    private static String normalizePrefix(String prefix) {
        if (prefix.isEmpty() || prefix.endsWith("/")) {
            return prefix;
        }
        return prefix + "/";
    }

    @Override
    public OutputStream openWrite(String sessionId, String account, String suffix) throws IOException {
        return new S3MultipartOutputStream(s3Client, bucket, objectKey(sessionId, account, suffix));
    }

    @Override
    public InputStream openRead(String sessionId, String account, String suffix) throws IOException {
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey(sessionId, account, suffix))
                    .build());
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    @Override
    public boolean exists(String sessionId, String account, String suffix) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey(sessionId, account, suffix))
                    .build());
            return true;
        } catch (SdkException e) {
            return false;
        }
    }

    @Override
    public String sizeOfAccount(String sessionId, String account) throws IOException {
        String prefix = sessionPrefix(sessionId);
        String accountFilePrefix = account + ".";
        long total = 0;
        for (S3Object object : listObjects(prefix + accountFilePrefix)) {
            String fileName = object.key().substring(prefix.length());
            if (fileName.startsWith(accountFilePrefix) && fileName.indexOf('.', accountFilePrefix.length()) < 0) {
                total += object.size();
            }
        }
        return HumanReadableSize.format(total);
    }

    @Override
    public String sizeOfSession(String sessionId) throws IOException {
        long total = 0;
        for (S3Object object : listObjects(sessionPrefix(sessionId))) {
            total += object.size();
        }
        return HumanReadableSize.format(total);
    }

    @Override
    public void deleteSession(String sessionId) throws IOException {
        deleteObjects(listObjects(sessionPrefix(sessionId)));
    }

    @Override
    public int deleteEmptyFiles() throws IOException {
        List<S3Object> empty = new ArrayList<>();
        for (S3Object object : listObjects(keyPrefix)) {
            if (object.size() == 0) {
                empty.add(object);
            }
        }
        deleteObjects(empty);
        return empty.size();
    }

    private String sessionPrefix(String sessionId) {
        return keyPrefix + sessionId + "/";
    }

    private String objectKey(String sessionId, String account, String suffix) {
        return sessionPrefix(sessionId) + account + "." + suffix;
    }

    private List<S3Object> listObjects(String prefix) throws IOException {
        try {
            List<S3Object> objects = new ArrayList<>();
            String continuationToken = null;
            boolean truncated;
            do {
                ListObjectsV2Request.Builder requestBuilder =
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
                objects.addAll(response.contents());
                truncated = Boolean.TRUE.equals(response.isTruncated());
                continuationToken = response.nextContinuationToken();
            } while (truncated);
            return objects;
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    private void deleteObjects(List<S3Object> objects) throws IOException {
        if (objects.isEmpty()) {
            return;
        }
        try {
            for (int start = 0; start < objects.size(); start += DELETE_BATCH_SIZE) {
                List<ObjectIdentifier> batch = new ArrayList<>();
                for (S3Object object : objects.subList(start, Math.min(start + DELETE_BATCH_SIZE, objects.size()))) {
                    batch.add(ObjectIdentifier.builder().key(object.key()).build());
                }
                s3Client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(batch).build())
                        .build());
            }
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }
}
