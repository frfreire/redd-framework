package br.com.redd.apigateway.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;

class DtoValidacaoTest {

    private static final String DIGEST = "sha256:" + "ab12".repeat(16);

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void abrir() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void fechar() {
        factory.close();
    }

    private static <T> Set<String> campos(T dto) {
        return validator.validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }

    // ---- aposentar / beneficiario (INV-08) ----

    @Test
    void beneficiarioDigestValidoPassa() {
        assertThat(validator.validate(new AposentarCreditoRequest("compensacao 2025", DIGEST))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Maria da Silva",                    // texto livre / nome
            "123.456.789-09",                    // CPF
            "maria@example.org",                 // e-mail
            "ORG-123:ref-9",                     // rotulo livre
            "sha256:abc",                        // curto
            "SHA256:" + "ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12", // prefixo em maiuscula
            "sha256:AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12AB12",      // hex maiusculo
            "ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12",              // sem prefixo
            " sha256:ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12",     // espaco na ponta
            "sha256:ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12\n",    // quebra de linha
            "sha256:zz12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12",      // nao hex
            "sha256:ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab12ab"      // 66 hex
    })
    void beneficiarioForaDoFormatoERecusado(String beneficiario) {
        var violacoes = validator.validate(new AposentarCreditoRequest("compensacao 2025", beneficiario));
        assertThat(violacoes).extracting(v -> v.getPropertyPath().toString()).contains("beneficiario");
        // a mensagem nao repete o valor recebido (pode ser dado pessoal)
        assertThat(violacoes).allSatisfy(v -> assertThat(v.getMessage()).doesNotContain(beneficiario.strip().isEmpty() ? "\u0000" : beneficiario.strip()));
    }

    @Test
    void beneficiarioAusenteOuBrancoERecusado() {
        assertThat(campos(new AposentarCreditoRequest("m", null))).contains("beneficiario");
        assertThat(campos(new AposentarCreditoRequest("m", "  "))).contains("beneficiario");
    }

    @Test
    void motivoObrigatorioELimitado() {
        assertThat(campos(new AposentarCreditoRequest(" ", DIGEST))).contains("motivo");
        assertThat(campos(new AposentarCreditoRequest("x".repeat(LimitesContrato.MAX_MOTIVO + 1), DIGEST)))
                .contains("motivo");
    }

    // ---- verificar / toneladasVerificadas (INV-12, ADR-0002) ----

    @Test
    void toneladasVerificadasEObrigatoria() {
        assertThat(campos(new VerificarMrvRequest(true, null, "ok"))).contains("toneladasVerificadas");
        assertThat(campos(new VerificarMrvRequest(false, null, "ok"))).contains("toneladasVerificadas");
    }

    @Test
    void aprovacaoExigeToneladasPositivas() {
        assertThat(validator.validate(new VerificarMrvRequest(true, 1500L, "ok"))).isEmpty();
        assertThat(validator.validate(new VerificarMrvRequest(true, 0L, "ok"))).isNotEmpty();
        assertThat(validator.validate(new VerificarMrvRequest(true, -5L, "ok"))).isNotEmpty();
    }

    @Test
    void rejeicaoExigeToneladasZero() {
        assertThat(validator.validate(new VerificarMrvRequest(false, 0L, "motivo"))).isEmpty();
        assertThat(validator.validate(new VerificarMrvRequest(false, 10L, "motivo"))).isNotEmpty();
    }

    @Test
    void aprovadoEJustificativaObrigatorios() {
        assertThat(campos(new VerificarMrvRequest(null, 1L, "ok"))).contains("aprovado");
        assertThat(campos(new VerificarMrvRequest(true, 1L, " "))).contains("justificativa");
    }

    // ---- emitir / submeter: toneladas inteiras e positivas ----

    @Test
    void emitirExigeToneladasPositivas() {
        assertThat(validator.validate(new EmitirCreditoRequest("c1", 1L))).isEmpty();
        assertThat(campos(new EmitirCreditoRequest("c1", null))).contains("toneladasCO2e");
        assertThat(campos(new EmitirCreditoRequest("c1", 0L))).contains("toneladasCO2e");
    }

    @Test
    void submeterValidaToneladasEPeriodo() {
        var ok = new SubmeterRelatorioMrvRequest("r1", "2024-01-01T00:00:00Z", "2024-12-31T00:00:00Z", 1000L, "hash");
        assertThat(validator.validate(ok)).isEmpty();
        assertThat(campos(new SubmeterRelatorioMrvRequest("r1", "2024-01-01T00:00:00Z", "2024-12-31T00:00:00Z", null, "h")))
                .contains("toneladasCO2eEstimadas");
        assertThat(campos(new SubmeterRelatorioMrvRequest("r1", "2024-01-01T00:00:00Z", "2024-12-31T00:00:00Z", 0L, "h")))
                .contains("toneladasCO2eEstimadas");
        // fim antes do inicio e data que nao e ISO instante
        assertThat(validator.validate(new SubmeterRelatorioMrvRequest("r1", "2024-12-31T00:00:00Z", "2024-01-01T00:00:00Z", 1L, "h")))
                .isNotEmpty();
        assertThat(validator.validate(new SubmeterRelatorioMrvRequest("r1", "01/01/2024", "2024-01-01T00:00:00Z", 1L, "h")))
                .isNotEmpty();
    }
}
