package br.com.redd.apigateway.exception;

import br.com.redd.apigateway.dto.ErroResponse;
import io.grpc.Status;
import org.hyperledger.fabric.client.CommitException;
import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.GatewayRuntimeException;
import org.hyperledger.fabric.client.SubmitException;
import org.hyperledger.fabric.protos.gateway.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Traduz toda falha de chamada ao chaincode/peer/orderer (via fabric-gateway)
 * para um JSON de erro consistente ({@link ErroResponse}), nunca deixando um
 * stack trace Java vazar para o corpo da resposta HTTP (o stack trace
 * completo e logado no servidor via {@link #log}).
 *
 * <p>Mapeamento de status HTTP (ver tambem AGENTS.md desta tarefa):</p>
 * <ul>
 *   <li>404 -- mensagem do chaincode contem "nao encontrado"/"não encontrado"</li>
 *   <li>403 -- mensagem contem "Apenas"+"pode" ou "Conflito de interesse"</li>
 *   <li>409 -- qualquer outra falha de regra de negocio/transicao de estado</li>
 *   <li>400 -- erro de validacao de payload (Bean Validation)</li>
 *   <li>502/503 -- erro de conectividade com peer/orderer</li>
 *   <li>500 -- fallback generico</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------
    // Erros vindos do fabric-gateway (endosso, commit, submit, evaluate)
    // ------------------------------------------------------------------

    @ExceptionHandler(EndorseException.class)
    public ResponseEntity<ErroResponse> handleEndorse(EndorseException e) {
        return traduzirErroChaincode(e);
    }

    @ExceptionHandler(SubmitException.class)
    public ResponseEntity<ErroResponse> handleSubmit(SubmitException e) {
        return traduzirErroChaincode(e);
    }

    @ExceptionHandler(CommitStatusException.class)
    public ResponseEntity<ErroResponse> handleCommitStatus(CommitStatusException e) {
        return traduzirErroChaincode(e);
    }

    @ExceptionHandler(CommitException.class)
    public ResponseEntity<ErroResponse> handleCommit(CommitException e) {
        log.error("Transacao commitada mas invalidada pelo peer (txId={}, code={})",
                e.getTransactionId(), e.getCode(), e);
        return corpo(HttpStatus.CONFLICT, "Transacao invalidada no commit",
                "txId=" + e.getTransactionId() + " code=" + e.getCode());
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErroResponse> handleGateway(GatewayException e) {
        return traduzirErroChaincode(e);
    }

    @ExceptionHandler(GatewayRuntimeException.class)
    public ResponseEntity<ErroResponse> handleGatewayRuntime(GatewayRuntimeException e) {
        log.error("Erro de runtime do fabric-gateway", e);
        return traduzirPorStatusGrpc(e.getStatus(), extrairMensagemDetalhada(e.getDetails(), e.getMessage()));
    }

    // ------------------------------------------------------------------
    // Erros de validacao de payload / X-Org invalido
    // ------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErroResponse> handleValidation(MethodArgumentNotValidException e) {
        String detalhe = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return corpo(HttpStatus.BAD_REQUEST, "Payload invalido", detalhe);
    }

    @ExceptionHandler(OrgInvalidaException.class)
    public ResponseEntity<ErroResponse> handleOrgInvalida(OrgInvalidaException e) {
        return corpo(HttpStatus.BAD_REQUEST, "Header X-Org invalido", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErroResponse> handleIllegalArgument(IllegalArgumentException e) {
        return corpo(HttpStatus.BAD_REQUEST, "Requisicao invalida", e.getMessage());
    }

    // ------------------------------------------------------------------
    // Fallback generico -- nunca vaza stacktrace
    // ------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErroResponse> handleGenerico(Exception e) {
        log.error("Erro nao mapeado ao processar requisicao", e);
        return corpo(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado", e.getMessage());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResponseEntity<ErroResponse> traduzirErroChaincode(GatewayException e) {
        log.warn("Erro retornado pelo chaincode/peer: {}", e.getMessage());
        String mensagem = extrairMensagemDetalhada(e.getDetails(), e.getMessage());
        return traduzirPorStatusGrpc(e.getStatus(), mensagem);
    }

    /**
     * Prioriza a mensagem original do chaincode (via
     * {@code ErrorDetail.getMessage()}, preenchido pelo peer com a
     * {@code ChaincodeException} lancada em {@code RedContract}) sobre a
     * mensagem generica do status gRPC.
     */
    private String extrairMensagemDetalhada(List<ErrorDetail> detalhes, String fallback) {
        if (detalhes != null && !detalhes.isEmpty()) {
            return detalhes.stream()
                    .map(ErrorDetail::getMessage)
                    .filter(m -> m != null && !m.isBlank())
                    .collect(Collectors.joining("; "));
        }
        return fallback != null ? fallback : "Erro desconhecido retornado pela rede Fabric.";
    }

    private ResponseEntity<ErroResponse> traduzirPorStatusGrpc(Status status, String mensagem) {
        Status.Code code = status != null ? status.getCode() : Status.Code.UNKNOWN;

        if (code == Status.Code.UNAVAILABLE) {
            return corpo(HttpStatus.SERVICE_UNAVAILABLE,
                    "Peer ou orderer indisponivel", mensagem);
        }
        if (code == Status.Code.DEADLINE_EXCEEDED) {
            return corpo(HttpStatus.GATEWAY_TIMEOUT,
                    "Timeout ao comunicar com peer/orderer", mensagem);
        }

        String msgLower = mensagem != null ? mensagem.toLowerCase(Locale.ROOT) : "";

        if (msgLower.contains("nao encontrado") || msgLower.contains("não encontrado")) {
            return corpo(HttpStatus.NOT_FOUND, "Recurso nao encontrado", mensagem);
        }
        if ((msgLower.contains("apenas") && msgLower.contains("pode")) || msgLower.contains("conflito de interesse")) {
            return corpo(HttpStatus.FORBIDDEN, "Operacao nao autorizada para esta identidade", mensagem);
        }
        if (code == Status.Code.INVALID_ARGUMENT || code == Status.Code.FAILED_PRECONDITION
                || code == Status.Code.ABORTED || code == Status.Code.ALREADY_EXISTS
                || code == Status.Code.UNKNOWN || code == Status.Code.PERMISSION_DENIED) {
            return corpo(HttpStatus.CONFLICT, "Falha de regra de negocio", mensagem);
        }

        return corpo(HttpStatus.INTERNAL_SERVER_ERROR, "Erro inesperado na rede Fabric", mensagem);
    }

    private ResponseEntity<ErroResponse> corpo(HttpStatus status, String erro, String detalhe) {
        return ResponseEntity.status(status).body(new ErroResponse(erro, detalhe));
    }
}
