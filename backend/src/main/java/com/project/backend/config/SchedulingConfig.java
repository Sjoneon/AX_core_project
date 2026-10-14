package com.project.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Daily retention purge of tool call records and hourly cleanup of idle rate-limit windows. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
