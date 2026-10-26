package dev.souradip.ledgerline;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class TransferApiTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void postsATransferAndReplaysItOnRetry() throws Exception {
        long from = ledger.openAccount(1_000);
        long to = ledger.openAccount(0);
        String body = """
                {"fromAccountId": %d, "toAccountId": %d, "amountMinor": 250, "currency": "USD"}
                """.formatted(from, to);

        mvc.perform(post("/transfers").header("Idempotency-Key", "api-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.amountMinor").value(250));

        mvc.perform(post("/transfers").header("Idempotency-Key", "api-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(get("/accounts/{id}", from))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(750));
    }

    @Test
    void requiresAnIdempotencyKey() throws Exception {
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\": 1, \"toAccountId\": 2, \"amountMinor\": 1, \"currency\": \"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void rejectsNonPositiveAmounts() throws Exception {
        mvc.perform(post("/transfers").header("Idempotency-Key", "bad-amount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\": 1, \"toAccountId\": 2, \"amountMinor\": -5, \"currency\": \"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsCurrencyMismatch() throws Exception {
        long from = ledger.openAccount(1_000);
        long to = ledger.openAccount(0);
        mvc.perform(post("/transfers").header("Idempotency-Key", "fx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\": %d, \"toAccountId\": %d, \"amountMinor\": 5, \"currency\": \"EUR\"}".formatted(from, to)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }
}
