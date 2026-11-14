package com.xops.hub;

import com.xops.hub.config.XOpsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(XOpsProperties.class)
public class XCommunityHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(XCommunityHubApplication.class, args);
    }
}
