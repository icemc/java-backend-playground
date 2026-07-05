package com.ludovictemgoua.imdb;

import org.springframework.boot.SpringApplication;

public class TestImdbApplication {

	public static void main(String[] args) {
		SpringApplication.from(ImdbApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
