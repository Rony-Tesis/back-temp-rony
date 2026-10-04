package com.back.rony.backrony;

import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RonyProperties.class)
public class BackRonyApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackRonyApplication.class, args);
    }
}
