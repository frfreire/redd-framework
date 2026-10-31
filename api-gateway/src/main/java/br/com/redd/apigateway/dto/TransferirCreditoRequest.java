package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;

/** Payload de {@code POST /api/v1/creditos/{id}/transferir}. */
public record TransferirCreditoRequest(
        @NotBlank String novoProprietarioMspId) {
}
