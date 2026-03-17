package com.example.batchs3;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableBatchProcessing
public class BatchConfig {

    @Bean
    public Job s3FileJob(JobRepository jobRepository,
                         Step s3FileStep,
                         JobExecutionListener jobStatusListener) {
        return new JobBuilder("s3FileJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobStatusListener)
                .start(s3FileStep)
                .build();
    }

    @Bean
    public Step s3FileStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           Tasklet s3CopyTasklet) {

        return new StepBuilder("s3FileStep", jobRepository)
                .tasklet(s3CopyTasklet, transactionManager)
                .build();
    }

    @Bean(name = "lineReader")
    @JobScope
    @Profile("dit")
    public ItemReader<String> lineReaderDit(
            @Value("#{jobParameters['bucketName']}") String bucketName,
            @Value("#{jobParameters['objectKey']}") String objectKey,
            S3LineReaderFactory s3LineReaderFactory) {
        return s3LineReaderFactory.create(bucketName, objectKey);
    }

    @Bean(name = "lineReader")
    @JobScope
    @Profile("local")
    public ItemReader<String> lineReaderLocal(
            @Value("#{jobParameters['localPath']}") String localPath) {
        FlatFileItemReader<String> reader = new FlatFileItemReader<>();
        reader.setResource(new FileSystemResource(localPath));
        reader.setLineMapper((line, lineNumber) -> line);
        return reader;
    }

    @Bean
    @JobScope
    public Tasklet s3CopyTasklet(
            ItemReader<String> lineReader,
            @Value("#{jobParameters['bucketName']}") String bucketName,
            @Value("#{jobParameters['objectKey']}") String objectKey,
            S3LineWriterFactory s3LineWriterFactory) {

        String archiveKey = "archive/" + objectKey;
        return (contribution, chunkContext) -> {
            long processed = 0L;
            try (S3LineWriterFactory.S3LineWriter writer =
                         s3LineWriterFactory.create(bucketName, archiveKey)) {
                String line;
                while ((line = lineReader.read()) != null) {
                    writer.writeLine(line);
                    processed++;
                }
            } catch (Exception e) {
                contribution.setExitStatus(new ExitStatus("FAILED_S3_COPY", e.getMessage()));
                throw e;
            }

            contribution.setExitStatus(new ExitStatus("COMPLETED_S3_COPY",
                    "linesProcessed=" + processed));
            return RepeatStatus.FINISHED;
        };
    }
}

