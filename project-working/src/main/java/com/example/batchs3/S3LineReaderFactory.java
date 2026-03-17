package com.example.batchs3;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@Component
public class S3LineReaderFactory {

    private final S3Client s3Client;

    public S3LineReaderFactory(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public ItemReader<String> create(String bucketName, String objectKey) {
        return new ItemReader<>() {
            private BufferedReader reader;

            @Override
            public String read() throws Exception {
                if (reader == null) {
                    GetObjectRequest req = GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .build();
                    ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(req);
                    reader = new BufferedReader(
                            new InputStreamReader(s3Object, StandardCharsets.UTF_8));
                }
                String line = reader.readLine();
                if (line == null) {
                    reader.close();
                }
                return line;
            }
        };
    }
}

