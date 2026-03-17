package com.example.batchs3;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

@Component
public class StepStatusListener implements StepExecutionListener {

    @Override
    public void beforeStep(StepExecution stepExecution) {
        System.out.println("Step starting: " + stepExecution.getStepName());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        System.out.println("Step finished: " + stepExecution.getStepName()
                + " with status=" + stepExecution.getStatus()
                + ", exitStatus=" + stepExecution.getExitStatus());
        return stepExecution.getExitStatus();
    }
}

