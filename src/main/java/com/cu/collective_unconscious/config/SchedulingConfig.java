package com.cu.collective_unconscious.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's @Scheduled processing (for {@code TicketExpiryScheduler}) — but only outside
 * the "test" profile, so the expiry sweep never fires mid-test and makes ticket-state assertions
 * non-deterministic. Tests exercise the sweep logic directly instead.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
