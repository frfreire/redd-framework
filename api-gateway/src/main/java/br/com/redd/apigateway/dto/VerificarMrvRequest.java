package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Payload de {@code POST /api/v1/mrv/{id}/verificar}. */
public record VerificarMrvRequest(
        @NotNull Boolean aprovado,
        @NotBlank String justificativa) {
}
