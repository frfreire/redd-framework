package br.com.redd.apigateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Payload de {@code POST /api/v1/projetos/{id}/mrv}. O {@code idProjeto} e
 * sempre o {@code {id}} do path, nunca aceito deste corpo.
 *
 * <p>{@code toneladasCO2eEstimadas}: inteiro {@code long} em kgCO2e
 * (ADR-0002), nunca fracionario. Periodos em ISO-8601 instantaneo
 * ({@code 2024-01-01T00:00:00Z}), como o contrato exige.</p>
 */
public record SubmeterRelatorioMrvRequest(
        @NotBlank @Size(max = LimitesContrato.MAX_ID) String idRelatorio,
        @NotBlank String periodoInicioIso,
        @NotBlank String periodoFimIso,
        @NotNull(message = "toneladasCO2eEstimadas e obrigatorio (inteiro em kgCO2e)")
        @Positive(message = "toneladasCO2eEstimadas deve ser inteiro positivo em kgCO2e")
        Long toneladasCO2eEstimadas,
        @NotBlank String hashEvidencia) {

    @JsonIgnore
    @AssertTrue(message = "periodoInicioIso e periodoFimIso devem ser ISO-8601 (ex.: 2024-01-01T00:00:00Z) com fim >= inicio")
    public boolean isPeriodoValido() {
        if (periodoInicioIso == null || periodoFimIso == null
                || periodoInicioIso.isBlank() || periodoFimIso.isBlank()) {
            return true; // ja coberto por @NotBlank
        }
        try {
            return !Instant.parse(periodoFimIso).isBefore(Instant.parse(periodoInicioIso));
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }
}
