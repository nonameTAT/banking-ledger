package com.owo.banking_ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@WithMockUser(username = "integration-tests", authorities = "SCOPE_ledger:admin")
class BankingLedgerApplicationTests {

	@Test
	void contextLoads() {
	}

}
