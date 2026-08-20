package com.arcticblu.subscriptioncapacity;

import org.springframework.boot.SpringApplication;

public class TestSubscriptionCapacityApplication {

	public static void main(String[] args) {
		SpringApplication.from(SubscriptionCapacityApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
