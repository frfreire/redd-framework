package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Payload de {@code POST /api/v1/projetos}. */
public record RegistrarProjetoRequest(
        @NotBlank String id,
        @NotBlank String nome,
        @Positive double areaHectares,
        @NotBlank String metodologia) {
}
