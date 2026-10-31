package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;

/** Payload de {@code POST /api/v1/creditos/{id}/aposentar}. */
public record AposentarCreditoRequest(
        @NotBlank String motivo) {
}
