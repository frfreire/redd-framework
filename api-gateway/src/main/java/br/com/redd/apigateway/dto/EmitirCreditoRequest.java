package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Payload de {@code POST /api/v1/mrv/{id}/emitir-credito}. O
 * {@code idRelatorio} e sempre o {@code {id}} do path.
 */
public record EmitirCreditoRequest(
        @NotBlank String idCredito,
        @Positive double toneladasCO2e) {
}
