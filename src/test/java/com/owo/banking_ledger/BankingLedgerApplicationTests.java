package com.owo.banking_ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BankingLedgerApplicationTests {

	@Test
	void contextLoads() {
	}

}
