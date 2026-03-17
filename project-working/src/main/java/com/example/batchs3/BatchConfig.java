package com.example.batchs3;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
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
    public Job s3FileJob(JobRepository jobRepository, Step s3FileStep) {
        return new JobBuilder("s3FileJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(s3FileStep)
                .build();
    }

    @Bean
    @JobScope
    public Step s3FileStep(JobRepository jobRepository,
                           PlatformTransactionManager transactionManager,
                           ItemReader<String> lineReader,
                           ItemProcessor<String, String> lineProcessor,
                           ItemWriter<String> s3ArchiveWriter) {

        return new StepBuilder("s3FileStep", jobRepository)
                .<String, String>chunk(5000, transactionManager)
                .reader(lineReader)
                .processor(lineProcessor)
                .writer(s3ArchiveWriter)
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
    public ItemProcessor<String, String> lineProcessor() {
        return item -> item;
    }

    @Bean
    @JobScope
    public ItemWriter<String> s3ArchiveWriter(
            @Value("#{jobParameters['bucketName']}") String bucketName,
            @Value("#{jobParameters['objectKey']}") String objectKey,
            S3LineWriterFactory s3LineWriterFactory) {

        String archiveKey = "archive/" + objectKey;
        return items -> {
            try (S3LineWriterFactory.S3LineWriter writer =
                         s3LineWriterFactory.create(bucketName, archiveKey)) {
                for (String line : items) {
                    writer.writeLine(line);
                }
            }
        };
    }
}

