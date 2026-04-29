package com.avijeet.nykaa.service;

import com.avijeet.nykaa.exception.MinioException;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MinioService {
    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${minio.url}")
    private String minioUrl;

    public String uploadImage(MultipartFile file) {
        try {
            BucketExistsArgs bucketExistsArgs = BucketExistsArgs.builder()
                    .bucket(bucketName)
                    .build();

            boolean found = minioClient.bucketExists(bucketExistsArgs);
            if(!found) {
                // create the bucket
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                setPublicReadPolicy();
            }

            String originalFileName = file.getOriginalFilename();
            String extension = originalFileName != null ? originalFileName.substring(originalFileName.lastIndexOf(".")) : "";
            String fileName = UUID.randomUUID() + extension;

            try (InputStream inputStream = file.getInputStream()) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(fileName)
                                .stream(inputStream, file.getSize(), -1)
                                .contentType(file.getContentType())
                                .build()
                );
            }

            return minioUrl + "/" + bucketName + "/" + fileName;

        } catch (Exception e) {
            throw new MinioException(e.getLocalizedMessage());
        }
    }

    private void setPublicReadPolicy() throws Exception {
        String policyJson = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": "*",
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(bucketName);

        minioClient.setBucketPolicy(
                SetBucketPolicyArgs.builder()
                        .bucket(bucketName)
                        .config(policyJson)
                        .build()
        );
    }
}
