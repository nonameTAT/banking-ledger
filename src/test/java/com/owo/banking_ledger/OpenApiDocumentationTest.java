package com.owo.banking_ledger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import org.springframework.context.annotation.Import;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesOpenApiSpec() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Banking Ledger API"))
                .andExpect(jsonPath("$.paths['/api/accounts']").exists())
                .andExpect(jsonPath("$.paths['/api/accounts'].get").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.AccountResponse.properties.accountKind")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/accounts/{id}/freeze']").exists())
                .andExpect(jsonPath("$.paths['/api/accounts/{id}/unfreeze']").exists())
                .andExpect(jsonPath("$.paths['/api/accounts/{accountId}/audit-logs']").exists())
                .andExpect(jsonPath("$.paths['/api/transfers']").exists())
                .andExpect(jsonPath("$.tags[*].name").value(hasItems(
                        "Accounts",
                        "Deposits",
                        "Withdrawals",
                        "Transfers",
                        "Ledger",
                        "Audit Logs")));
    }

    /**
     * A transfer is authorized against its source account only, so its
     * response must not describe the target. Checked against the whole
     * document so the field cannot come back under another schema either.
     */
    @Test
    void transferResponseDocumentsTheSourceBalanceOnly() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.components.schemas.TransferResponse.properties.sourceBalanceAfter")
                        .exists())
                .andExpect(content().string(not(containsString("targetBalanceAfter"))));
    }
}
