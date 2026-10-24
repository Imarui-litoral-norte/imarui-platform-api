package com.logimarui.receivables.infra.config;

import com.logimarui.receivables.core.model.Receivables.Policy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReceivablesConfig {
    @Bean
    Policy receivablesPolicy(
            @Value("${app.receivables.upcoming-days:7}") int upcoming,
            @Value("${app.receivables.recent-days:90}") int recent,
            @Value("${app.receivables.minimum-history-titles:5}") int minimum,
            @Value("${app.receivables.recurring-late-titles:2}") int recurrence) {
        return new Policy(upcoming, recent, minimum, recurrence);
    }
}

