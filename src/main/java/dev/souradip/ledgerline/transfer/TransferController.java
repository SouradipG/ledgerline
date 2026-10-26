package dev.souradip.ledgerline.transfer;

import dev.souradip.ledgerline.idempotency.StoredResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping("/transfers")
    public ResponseEntity<String> create(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        StoredResponse response = transfers.transfer(idempotencyKey, request);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", String.valueOf(response.replayed()))
                .body(response.body());
    }
}
