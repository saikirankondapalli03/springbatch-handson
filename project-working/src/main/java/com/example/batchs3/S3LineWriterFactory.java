package com.example.batchs3;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class S3LineWriterFactory {

    private final S3Client s3Client;

    public S3LineWriterFactory(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public S3LineWriter create(String bucketName, String objectKey) {
        return new S3LineWriter(bucketName, objectKey);
    }

    public class S3LineWriter implements AutoCloseable {
        private final String bucketName;
        private final String objectKey;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private S3LineWriter(String bucketName, String objectKey) {
            this.bucketName = bucketName;
            this.objectKey = objectKey;
        }

        public void writeLine(String line) throws IOException {
            buffer.write(line.getBytes(StandardCharsets.UTF_8));
            buffer.write('\n');
        }

        @Override
        public void close() {
            byte[] data = buffer.toByteArray();
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            s3Client.putObject(req, RequestBody.fromBytes(data));
        }
    }
}

