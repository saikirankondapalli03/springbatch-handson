package com.example.batchs3;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dit")
public class DitJobLauncher implements CommandLineRunner {

    private final JobLauncher jobLauncher;
    private final Job s3FileJob;

    @Value("${BUCKET_NAME:#{null}}")
    private String bucketNameEnv;

    @Value("${OBJECT_KEY:#{null}}")
    private String objectKeyEnv;

    @Value("${EVENT_TIME:#{null}}")
    private String eventTimeEnv;

    public DitJobLauncher(JobLauncher jobLauncher, Job s3FileJob) {
        this.jobLauncher = jobLauncher;
        this.s3FileJob = s3FileJob;
    }

    @Override
    public void run(String... args) throws Exception {
        String bucketName = bucketNameEnv;
        String objectKey = objectKeyEnv;
        String eventTime = eventTimeEnv != null ? eventTimeEnv : String.valueOf(System.currentTimeMillis());

        if (bucketName == null || objectKey == null) {
            throw new IllegalArgumentException("BUCKET_NAME and OBJECT_KEY environment variables must be set");
        }

        JobParameters jobParameters = new JobParametersBuilder()
                .addString("bucketName", bucketName)
                .addString("objectKey", objectKey)
                .addString("eventTime", eventTime)
                .toJobParameters();

        jobLauncher.run(s3FileJob, jobParameters);
    }
}

