package com.owo.banking_ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BankingLedgerApplication {

	public static void main(String[] args) {
		SpringApplication.run(BankingLedgerApplication.class, args);
	}

}
