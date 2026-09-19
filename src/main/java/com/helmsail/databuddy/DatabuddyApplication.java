package com.helmsail.databuddy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DatabuddyApplication {

	public static void main(String[] args) {
		SpringApplication.run(DatabuddyApplication.class, args);
	}

}
