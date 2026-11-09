package br.com.redd.apigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload de {@code POST /api/v1/creditos/{id}/aposentar}.
 *
 * <p>{@code beneficiario} e uma referencia opaca (INV-08): somente
 * {@code sha256:} + 64 hex minusculos. A API NAO calcula o digest e NAO
 * aceita texto livre nem identificador pessoal; o chamador calcula o hash
 * fora do ledger. Quem calcula e com que sal e decisao de dominio ainda em
 * aberto (ver ADR-0008, Proposto). A mensagem de validacao nao repete o
 * valor recebido.</p>
 */
public record AposentarCreditoRequest(
        @NotBlank(message = "motivo e obrigatorio")
        @Size(max = LimitesContrato.MAX_MOTIVO, message = "motivo excede o tamanho maximo")
        String motivo,

        @NotBlank(message = "beneficiario e obrigatorio")
        @Pattern(regexp = LimitesContrato.BENEFICIARIO_REGEX,
                message = "beneficiario fora do formato de digest (INV-08): esperado "
                        + LimitesContrato.BENEFICIARIO_FORMATO_DESCRICAO
                        + "; calcule o hash fora do ledger e envie somente o digest")
        String beneficiario) {
}
