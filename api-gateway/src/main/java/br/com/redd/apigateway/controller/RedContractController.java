package br.com.redd.apigateway.controller;

import br.com.redd.apigateway.dto.AposentarCreditoRequest;
import br.com.redd.apigateway.dto.EmitirCreditoRequest;
import br.com.redd.apigateway.dto.RegistrarProjetoRequest;
import br.com.redd.apigateway.dto.SubmeterRelatorioMrvRequest;
import br.com.redd.apigateway.dto.TransferirCreditoRequest;
import br.com.redd.apigateway.dto.VerificarMrvRequest;
import br.com.redd.apigateway.fabric.FabricGatewayService;
import br.com.redd.apigateway.fabric.OrgContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.hyperledger.fabric.client.CommitException;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expoe o contrato {@code RedContract} (chaincode {@code redd-creditos},
 * canal {@code redchannel}) via HTTP/JSON. A organizacao que assina cada
 * chamada e selecionada pelo header {@code X-Org} (ver
 * {@link br.com.redd.apigateway.filter.OrgHeaderFilter}).
 *
 * <p>O corpo JSON ja retornado pelo chaincode (via
 * {@code submitTransaction}/{@code evaluateTransaction}) e repassado
 * diretamente como corpo da resposta HTTP, sem reserializacao -- e um JSON
 * valido produzido pelo proprio {@code ObjectMapper} do chaincode.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "REDD+", description = "Ciclo de vida de projetos REDD+ e creditos de carbono")
public class RedContractController {

    private final FabricGatewayService fabricGatewayService;

    public RedContractController(FabricGatewayService fabricGatewayService) {
        this.fabricGatewayService = fabricGatewayService;
    }

    // ------------------------------------------------------------------
    // Projetos
    // ------------------------------------------------------------------

    @PostMapping(value = "/projetos", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Registra um novo projeto REDD+ (role esperado: developer)")
    public ResponseEntity<byte[]> registrarProjeto(@Valid @RequestBody RegistrarProjetoRequest req)
            throws GatewayException, CommitException {
        Contract contract = contract();
        byte[] json = contract.submitTransaction(
                "registrarProjeto",
                req.id(), req.nome(), String.valueOf(req.areaHectares()), req.metodologia());
        return ok(HttpStatus.CREATED, json);
    }

    @GetMapping(value = "/projetos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Consulta um projeto REDD+ por id")
    public ResponseEntity<byte[]> consultarProjeto(@PathVariable String id) throws GatewayException {
        byte[] json = contract().evaluateTransaction("consultarProjeto", id);
        return ok(HttpStatus.OK, json);
    }

    @GetMapping(value = "/projetos/{id}/creditos", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Lista os creditos de carbono emitidos para um projeto")
    public ResponseEntity<byte[]> listarCreditosPorProjeto(@PathVariable String id) throws GatewayException {
        byte[] json = contract().evaluateTransaction("listarCreditosPorProjeto", id);
        return ok(HttpStatus.OK, json);
    }

    // ------------------------------------------------------------------
    // MRV
    // ------------------------------------------------------------------

    @PostMapping(value = "/projetos/{id}/mrv", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Submete um relatorio de MRV para um projeto (role esperado: developer)")
    public ResponseEntity<byte[]> submeterRelatorioMrv(
            @PathVariable String id, @Valid @RequestBody SubmeterRelatorioMrvRequest req)
            throws GatewayException, CommitException {
        byte[] json = contract().submitTransaction(
                "submeterRelatorioMRV",
                req.idRelatorio(), id, req.periodoInicioIso(), req.periodoFimIso(),
                String.valueOf(req.toneladasCO2eEstimadas()), req.hashEvidencia());
        return ok(HttpStatus.CREATED, json);
    }

    @PostMapping(value = "/mrv/{id}/verificar", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Aprova ou rejeita um relatorio de MRV (role esperado: verifier)")
    public ResponseEntity<byte[]> verificarRelatorioMrv(
            @PathVariable String id, @Valid @RequestBody VerificarMrvRequest req)
            throws GatewayException, CommitException {
        byte[] json = contract().submitTransaction(
                "verificarRelatorioMRV", id, String.valueOf(req.aprovado()), req.justificativa());
        return ok(HttpStatus.OK, json);
    }

    @PostMapping(value = "/mrv/{id}/emitir-credito", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Emite um credito de carbono a partir de um relatorio MRV verificado (role esperado: developer)")
    public ResponseEntity<byte[]> emitirCredito(
            @PathVariable String id, @Valid @RequestBody EmitirCreditoRequest req)
            throws GatewayException, CommitException {
        byte[] json = contract().submitTransaction(
                "emitirCredito", req.idCredito(), id, String.valueOf(req.toneladasCO2e()));
        return ok(HttpStatus.CREATED, json);
    }

    // ------------------------------------------------------------------
    // Creditos
    // ------------------------------------------------------------------

    @GetMapping(value = "/creditos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Consulta um credito de carbono por id")
    public ResponseEntity<byte[]> consultarCredito(@PathVariable String id) throws GatewayException {
        byte[] json = contract().evaluateTransaction("consultarCredito", id);
        return ok(HttpStatus.OK, json);
    }

    @GetMapping(value = "/creditos/{id}/historico", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Historico de mutacoes de estado de um credito no ledger")
    public ResponseEntity<byte[]> historicoCredito(@PathVariable String id) throws GatewayException {
        byte[] json = contract().evaluateTransaction("historicoCredito", id);
        return ok(HttpStatus.OK, json);
    }

    @PostMapping(value = "/creditos/{id}/transferir", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transfere um credito de carbono para outra organizacao (role: proprietario atual)")
    public ResponseEntity<byte[]> transferirCredito(
            @PathVariable String id, @Valid @RequestBody TransferirCreditoRequest req)
            throws GatewayException, CommitException {
        byte[] json = contract().submitTransaction("transferirCredito", id, req.novoProprietarioMspId());
        return ok(HttpStatus.OK, json);
    }

    @PostMapping(value = "/creditos/{id}/aposentar", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Aposenta (retira de circulacao, de forma irreversivel) um credito de carbono (role: proprietario atual)")
    public ResponseEntity<byte[]> aposentarCredito(
            @PathVariable String id, @Valid @RequestBody AposentarCreditoRequest req)
            throws GatewayException, CommitException {
        byte[] json = contract().submitTransaction("aposentarCredito", id, req.motivo());
        return ok(HttpStatus.OK, json);
    }

    // ------------------------------------------------------------------

    private Contract contract() {
        return fabricGatewayService.getContract(OrgContext.get());
    }

    private ResponseEntity<byte[]> ok(HttpStatus status, byte[] json) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
