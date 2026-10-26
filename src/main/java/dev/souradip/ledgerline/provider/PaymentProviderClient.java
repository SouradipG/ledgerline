package dev.souradip.ledgerline.provider;

import dev.souradip.ledgerline.LedgerProperties;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for the external payment provider.
 *
 * <p>Every call has a connect and a read timeout. The JDK default for both is to wait forever,
 * which on a bad day turns one slow dependency into every worker thread stuck on a socket.
 */
@Component
public class PaymentProviderClient {

    private final RestClient http;

    PaymentProviderClient(RestClient.Builder builder, LedgerProperties properties) {
        LedgerProperties.Provider config = properties.provider();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build());
        factory.setReadTimeout(config.readTimeout());
        this.http = builder.baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    /** The provider dedupes on {@code paymentId}, so resubmitting the same id never charges twice. */
    public ProviderResult submit(String paymentId, long amountMinor, String currency) {
        try {
            return http.post()
                    .uri("/payments")
                    .header("Idempotency-Key", paymentId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentId", paymentId, "amountMinor", amountMinor, "currency", currency))
                    .exchange((request, response) -> toResult(response.getStatusCode(),
                            response.getStatusCode().is2xxSuccessful() || response.getStatusCode().value() == 422
                                    ? response.bodyTo(ProviderPayment.class) : null));
        } catch (ResourceAccessException e) {
            return new ProviderResult.Unknown("no response from provider: " + e.getMostSpecificCause());
        } catch (RestClientException e) {
            return new ProviderResult.Unknown(e.toString());
        }
    }

    public ProviderResult lookup(String paymentId) {
        try {
            return http.get()
                    .uri("/payments/{id}", paymentId)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() == 404) {
                            return new ProviderResult.NotFound();
                        }
                        return toResult(response.getStatusCode(),
                                response.getStatusCode().is2xxSuccessful() ? response.bodyTo(ProviderPayment.class) : null);
                    });
        } catch (RestClientException e) {
            return new ProviderResult.Unknown("status lookup failed: " + e.getMostSpecificCause());
        }
    }

    public List<ProviderPayment> settlements() {
        return http.get().uri("/settlements").retrieve().body(new ParameterizedTypeReference<>() {});
    }

    private static ProviderResult toResult(HttpStatusCode status, ProviderPayment body) {
        if (body != null && "SETTLED".equals(body.status())) {
            return new ProviderResult.Settled(body.reference());
        }
        if (body != null && "DECLINED".equals(body.status())) {
            return new ProviderResult.Declined(body.reason());
        }
        return new ProviderResult.Unknown("provider answered " + status.value());
    }
}
