package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S6: health keeps library behaviour when a contributor is DOWN (503 with actuator JSON, not the G10 500 text).
 * Liveness and readiness don't include ordinary contributors, so they stay UP (planner Q6: readiness excludes db).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ActuatorHealthDownTest.DownContributor.class})
class ActuatorHealthDownTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class DownContributor {

        @Bean
        HealthIndicator alwaysDownHealthIndicator() {
            return () -> Health.down().build();
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void healthIs503WhenAContributorIsDown() throws Exception {
        String body = mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).isNotEqualTo("Internal server error");
    }

    @Test
    void livenessStaysUpWhenAContributorIsDown() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readinessStaysUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
