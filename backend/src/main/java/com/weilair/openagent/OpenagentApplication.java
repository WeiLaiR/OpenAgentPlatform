package com.weilair.openagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("com.weilair.openagent")
public class OpenagentApplication {
	
	public static void main(String[] args) {
		SpringApplication.run(OpenagentApplication.class, args);
	}
	
}
