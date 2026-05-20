package com.ludo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LudoApplication {
	public static void main(String[] args) {
		SpringApplication.run(LudoApplication.class, args);
	}
}
