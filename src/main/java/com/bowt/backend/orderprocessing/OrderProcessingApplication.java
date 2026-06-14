package com.bowt.backend.orderprocessing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class OrderProcessingApplication {
    static {
        // Prevents HikariCP from sending an unrecognized timezone to PostgreSQL
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(OrderProcessingApplication.class, args);
    }
}