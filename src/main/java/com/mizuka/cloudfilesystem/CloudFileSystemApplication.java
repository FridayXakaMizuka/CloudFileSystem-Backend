package com.mizuka.cloudfilesystem;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.mizuka.cloudfilesystem.mapper")
public class CloudFileSystemApplication {

    public static void main(String[] args)
    {
        SpringApplication.run(CloudFileSystemApplication.class, args);
    }

}
