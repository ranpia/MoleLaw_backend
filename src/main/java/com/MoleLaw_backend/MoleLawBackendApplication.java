package com.MoleLaw_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.PropertySource;

@SpringBootApplication
@EntityScan(basePackages = "com.MoleLaw_backend.domain.entity")
@PropertySource(value = "classpath:.env", ignoreResourceNotFound = true)
public class MoleLawBackendApplication {
	public static void main(String[] args) {
		SpringApplication.run(MoleLawBackendApplication.class, args);
	}
}
