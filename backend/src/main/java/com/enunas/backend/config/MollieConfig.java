package com.enunas.backend.config;

import com.mollie.mollie.Client;
import com.mollie.mollie.models.components.Security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MollieConfig {

    @Value("${mollie.api.key}")
    private String apiKey;

    @Bean
    public Client mollieClient() {
        return Client.builder()
                .security(Security.builder()
                        .apiKey(apiKey)
                        .build())
                .build();
    }

}
