package io.zmbackup.aws;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

final class S3MultipartOutputStream extends OutputStream {

    static final int PART_SIZE_BYTES = 8 * 1024 * 1024;

    private static final Logger LOG = Logger.getLogger(S3MultipartOutputStream.class.getName());

    private final S3Client s3Client;
    private final String bucket;
    private final String key;
    private final byte[] buffer = new byte[PART_SIZE_BYTES];
    private final List<CompletedPart> completedParts = new ArrayList<>();

    private int bufferLength;
    private String uploadId;
    private int nextPartNumber = 1;
    private boolean closed;

    S3MultipartOutputStream(S3Client s3Client, String bucket, String key) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.key = key;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int remaining = len;
        int offset = off;
        while (remaining > 0) {
            int spaceInBuffer = buffer.length - bufferLength;
            int toCopy = Math.min(spaceInBuffer, remaining);
            System.arraycopy(b, offset, buffer, bufferLength, toCopy);
            bufferLength += toCopy;
            offset += toCopy;
            remaining -= toCopy;
            if (bufferLength == buffer.length) {
                uploadPart();
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (uploadId == null) {
            putWholeObject();
            return;
        }
        if (bufferLength > 0) {
            uploadPart();
        }
        try {
            s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());
        } catch (SdkException e) {
            abort();
            throw new IOException(e);
        }
    }

    private void putWholeObject() throws IOException {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(Arrays.copyOf(buffer, bufferLength)));
        } catch (SdkException e) {
            throw new IOException(e);
        }
    }

    private void uploadPart() throws IOException {
        try {
            if (uploadId == null) {
                uploadId = s3Client.createMultipartUpload(
                                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build())
                        .uploadId();
            }
            int partNumber = nextPartNumber++;
            UploadPartResponse response = s3Client.uploadPart(
                    UploadPartRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .build(),
                    RequestBody.fromBytes(Arrays.copyOf(buffer, bufferLength)));
            completedParts.add(CompletedPart.builder()
                    .partNumber(partNumber)
                    .eTag(response.eTag())
                    .build());
            bufferLength = 0;
        } catch (SdkException e) {
            abort();
            throw new IOException(e);
        }
    }

    private void abort() {
        if (uploadId == null) {
            return;
        }
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (SdkException e) {
            LOG.log(Level.WARNING, "Failed to abort multipart upload " + uploadId + " for " + key, e);
        }
    }
}
