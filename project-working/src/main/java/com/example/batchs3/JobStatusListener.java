package com.example.batchs3;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JobStatusListener implements JobExecutionListener {

    private final JdbcTemplate jdbcTemplate;

    public JobStatusListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String externalJobId = jobExecution.getJobParameters().getString("externalJobId");
        Long jobExecutionId = jobExecution.getId();

        jdbcTemplate.update(
                "INSERT INTO job_audit " +
                        "(job_execution_id, job_name, external_job_id, status, start_time) " +
                        "VALUES (?, ?, ?, ?, ?)",
                jobExecutionId,
                jobExecution.getJobInstance().getJobName(),
                externalJobId,
                BatchStatus.STARTING.name(),
                jobExecution.getStartTime()
        );
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String externalJobId = jobExecution.getJobParameters().getString("externalJobId");
        Long jobExecutionId = jobExecution.getId();

        BatchStatus status = jobExecution.getStatus();
        String exitCode = jobExecution.getExitStatus().getExitCode();
        String exitDescription = jobExecution.getExitStatus().getExitDescription();

        int updated = jdbcTemplate.update(
                "UPDATE job_audit SET status = ?, end_time = ?, exit_code = ?, exit_message = ? " +
                        "WHERE job_execution_id = ?",
                status.name(),
                jobExecution.getEndTime(),
                exitCode,
                exitDescription,
                jobExecutionId
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO job_audit " +
                            "(job_execution_id, job_name, external_job_id, status, start_time, end_time, exit_code, exit_message) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    jobExecutionId,
                    jobExecution.getJobInstance().getJobName(),
                    externalJobId,
                    status.name(),
                    jobExecution.getStartTime(),
                    jobExecution.getEndTime(),
                    exitCode,
                    exitDescription
            );
        }
    }
}

