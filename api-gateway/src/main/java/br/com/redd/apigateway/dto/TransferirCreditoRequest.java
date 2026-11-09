package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload de {@code POST /api/v1/creditos/{id}/transferir}. */
public record TransferirCreditoRequest(
        @NotBlank @Size(max = LimitesContrato.MAX_ID) String novoProprietarioMspId) {
}
