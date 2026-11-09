package br.com.redd.apigateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Payload de {@code POST /api/v1/mrv/{id}/verificar}.
 *
 * <p>{@code toneladasVerificadas}: inteiro {@code long} em kgCO2e (ADR-0002;
 * nunca fracionario). Obrigatorio. Regra do contrato (INV-12): na aprovacao
 * deve ser &gt; 0; na rejeicao deve ser exatamente 0.</p>
 */
public record VerificarMrvRequest(
        @NotNull(message = "aprovado e obrigatorio")
        Boolean aprovado,

        @NotNull(message = "toneladasVerificadas e obrigatorio (inteiro em kgCO2e; 0 ao rejeitar)")
        Long toneladasVerificadas,

        @NotBlank(message = "justificativa e obrigatoria")
        String justificativa) {

    @JsonIgnore
    @AssertTrue(message = "toneladasVerificadas deve ser > 0 ao aprovar e 0 ao rejeitar (kgCO2e, inteiro)")
    public boolean isToneladasVerificadasCoerente() {
        if (aprovado == null || toneladasVerificadas == null) {
            return true; // ja coberto por @NotNull
        }
        return aprovado ? toneladasVerificadas > 0 : toneladasVerificadas == 0;
    }
}
