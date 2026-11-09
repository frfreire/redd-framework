package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Payload de {@code POST /api/v1/projetos}. {@code areaHectares} e
 * {@code double} tambem no contrato (area nao e quantidade de carbono).
 */
public record RegistrarProjetoRequest(
        @NotBlank @Size(max = LimitesContrato.MAX_ID) String id,
        @NotBlank String nome,
        @Positive double areaHectares,
        @NotBlank String metodologia) {
}
