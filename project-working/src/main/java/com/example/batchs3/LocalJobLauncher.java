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
@Profile("local")
public class LocalJobLauncher implements CommandLineRunner {

    private final JobLauncher jobLauncher;
    private final Job s3FileJob;

    @Value("${app.local-path}")
    private String localPath;

    @Value("${app.bucket-name}")
    private String bucketName;

    @Value("${app.object-key}")
    private String objectKey;

    @Value("${app.event-time:#{null}}")
    private String eventTime;

    public LocalJobLauncher(JobLauncher jobLauncher, Job s3FileJob) {
        this.jobLauncher = jobLauncher;
        this.s3FileJob = s3FileJob;
    }

    @Override
    public void run(String... args) throws Exception {
        String effectiveEventTime = eventTime != null ? eventTime : String.valueOf(System.currentTimeMillis());

        JobParameters jobParameters = new JobParametersBuilder()
                .addString("localPath", localPath)
                .addString("bucketName", bucketName)
                .addString("objectKey", objectKey)
                .addString("eventTime", effectiveEventTime)
                .toJobParameters();

        jobLauncher.run(s3FileJob, jobParameters);
    }
}

