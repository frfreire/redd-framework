package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Payload de {@code POST /api/v1/projetos/{id}/mrv}. O {@code idProjeto} e
 * sempre o {@code {id}} do path, nunca aceito deste corpo.
 */
public record SubmeterRelatorioMrvRequest(
        @NotBlank String idRelatorio,
        @NotBlank String periodoInicioIso,
        @NotBlank String periodoFimIso,
        @Positive double toneladasCO2eEstimadas,
        @NotBlank String hashEvidencia) {
}
