package com.smi.zimexplorer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmiZimExplorerApplication {


	public static void main(String[] args) {
		int requiredMajorVersion = 17; // Change this as needed
		int runningVersion = Runtime.version().feature(); // Gets major version number

		if (runningVersion < requiredMajorVersion) {
			System.err.println("Error: This application requires Java " + requiredMajorVersion + " but found " + runningVersion);
			System.exit(1);
		}

		SpringApplication.run(SmiZimExplorerApplication.class, args);
	}

}
