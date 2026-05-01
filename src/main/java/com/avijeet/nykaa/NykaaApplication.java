package com.avijeet.nykaa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class NykaaApplication {

    public static void main(String[] args) {
        SpringApplication.run(NykaaApplication.class, args);
    }

}
