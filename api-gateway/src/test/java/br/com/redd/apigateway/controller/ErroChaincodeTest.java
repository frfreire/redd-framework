package br.com.redd.apigateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.redd.apigateway.dto.ErroResponse;
import br.com.redd.apigateway.exception.GlobalExceptionHandler;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.protos.gateway.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Mapeamento de mensagens do contrato ({@code RedContractException}) para
 * HTTP, sem rede. O corpo nunca carrega stack trace.
 */
class ErroChaincodeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private ResponseEntity<ErroResponse> endosso(String mensagemDoContrato) {
        // O peer devolve a mensagem do chaincode como ErrorDetail dentro do
        // com.google.rpc.Status (trailer grpc-status-details-bin).
        ErrorDetail detalhe = ErrorDetail.newBuilder().setMessage(mensagemDoContrato).build();
        com.google.rpc.Status rpc = com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.ABORTED.value())
                .setMessage("endorse failed")
                .addDetails(com.google.protobuf.Any.pack(detalhe))
                .build();
        EndorseException e = new EndorseException("tx1", StatusProto.toStatusRuntimeException(rpc));
        return handler.handleEndorse(e);
    }

    @Test
    void naoEncontradoVira404() {
        assertEquals(HttpStatus.NOT_FOUND, endosso("Credito com id 'c9' nao encontrado.").getStatusCode());
    }

    @Test
    void papelOuProprietarioErradoVira403() {
        assertEquals(HttpStatus.FORBIDDEN,
                endosso("Apenas o proprietario atual do credito pode aposenta-lo. Proprietario atual: X").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                endosso("Conflito de interesse: o MSP 'DeveloperMSP' e o mesmo proponente").getStatusCode());
    }

    @Test
    void entradaRecusadaPeloContratoVira422() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, endosso(
                "beneficiario fora do formato de digest (INV-08/INV-14): esperado sha256:<64 ...>").getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                endosso("pageSize deve estar entre 1 e 100 (recebido: 500).").getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, endosso(
                "toneladasVerificadas deve ser inteiro positivo em kgCO2e (recebido: 0).").getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, endosso(
                "motivo contem padrao de identificador pessoal direto (e-mail, CPF...; INV-08).").getStatusCode());
    }

    @Test
    void transicaoDeEstadoInvalidaVira409() {
        assertEquals(HttpStatus.CONFLICT,
                endosso("Credito 'c1' ja esta APOSENTADO (aposentadoria e irreversivel).").getStatusCode());
        assertEquals(HttpStatus.CONFLICT, endosso(
                "Emissao excede as toneladas verificadas do relatorio 'r1': emitidas (0) + solicitado (9)")
                .getStatusCode());
    }

    @Test
    void corpoNaoVazaStackTrace() {
        ResponseEntity<ErroResponse> r = endosso("Credito com id 'c9' nao encontrado.");
        ErroResponse corpo = r.getBody();
        assertNotNull(corpo);
        String texto = corpo.erro() + " " + corpo.detalhe();
        assertFalse(texto.contains("\tat "));
        assertFalse(texto.contains("Exception"));
        assertTrue(texto.contains("nao encontrado"));
    }

    @Test
    void excecaoGenericaNaoExpoeMensagemInterna() {
        ResponseEntity<ErroResponse> r = handler.handleGenerico(new RuntimeException("senha=segredo jdbc://interno"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertFalse(r.getBody().detalhe().contains("segredo"));
    }
}
