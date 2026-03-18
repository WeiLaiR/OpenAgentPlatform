package com.weilair.openagent;

import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
// 这里显式限制只扫描标了 @Mapper 的接口，避免把 knowledge/service 等业务接口误注册成 MyBatis 代理。
@MapperScan(basePackages = "com.weilair.openagent", annotationClass = Mapper.class)
public class OpenagentApplication {
	
	public static void main(String[] args) {
		SpringApplication.run(OpenagentApplication.class, args);
	}
	
}
