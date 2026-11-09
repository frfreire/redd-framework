package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload de {@code POST /api/v1/mrv/{id}/emitir-credito}. O
 * {@code idRelatorio} e sempre o {@code {id}} do path.
 *
 * <p>{@code toneladasCO2e}: inteiro {@code long} em kgCO2e (ADR-0002), nunca
 * fracionario. Conversao de tCO2e decimal e feita pelo cliente.</p>
 */
public record EmitirCreditoRequest(
        @NotBlank @Size(max = LimitesContrato.MAX_ID) String idCredito,
        @NotNull(message = "toneladasCO2e e obrigatorio (inteiro em kgCO2e)")
        @Positive(message = "toneladasCO2e deve ser inteiro positivo em kgCO2e")
        Long toneladasCO2e) {
}
