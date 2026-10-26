package dev.souradip.ledgerline.payout;

import dev.souradip.ledgerline.api.ApiException;
import dev.souradip.ledgerline.idempotency.StoredResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payouts")
@Validated
public class PayoutController {

    private final PayoutService service;
    private final PayoutRepository payouts;

    PayoutController(PayoutService service, PayoutRepository payouts) {
        this.service = service;
        this.payouts = payouts;
    }

    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody PayoutRequest request) {
        StoredResponse response = service.request(idempotencyKey, request);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", String.valueOf(response.replayed()))
                .body(response.body());
    }

    @GetMapping("/{id}")
    public PayoutView get(@PathVariable UUID id) {
        return payouts.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYOUT_NOT_FOUND", "payout " + id + " does not exist"));
    }
}
