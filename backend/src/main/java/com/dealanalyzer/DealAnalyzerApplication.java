package com.dealanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DealAnalyzerApplication {
  public static void main(String[] args) {
    SpringApplication.run(DealAnalyzerApplication.class, args);
  }
}
