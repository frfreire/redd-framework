package br.com.redd.apigateway.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.redd.apigateway.fabric.FabricGatewayService;
import br.com.redd.apigateway.fabric.Organizacao;
import br.com.redd.apigateway.config.FabricProperties;
import org.hyperledger.fabric.client.Contract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do controller sem rede Fabric: {@link FabricGatewayService} e
 * {@link Contract} sao dubles. NAO e teste de integracao com a rede nem com o
 * chaincode instalado; verifica so a montagem de argumentos (ordem, tipo) e a
 * validacao de borda contra a assinatura de {@code RedContract}.
 */
@WebMvcTest(RedContractController.class)
@Import(RedContractControllerTest.Dubles.class)
class RedContractControllerTest {

    private static final String DIGEST = "sha256:" + "0f".repeat(32);
    private static final byte[] JSON = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;

    /** Chamadas ao {@link Contract}: [tipo, funcao, args...], tipo = submit|evaluate. */
    static final List<List<String>> CHAMADAS = new ArrayList<>();
    /** Organizacoes pedidas ao servico, na ordem. */
    static final List<Organizacao> ORGS = new ArrayList<>();

    /**
     * Dubles escritos a mao (sem Mockito): o Mockito inline nao instrumenta
     * classes no Java 25 (Byte Buddy so ate 23) neste ambiente.
     */
    @TestConfiguration
    static class Dubles {
        @Bean
        FabricGatewayService fabricGatewayService() {
            return new FabricGatewayService(new FabricProperties()) {
                @Override
                public Contract getContract(Organizacao org) {
                    ORGS.add(org);
                    return (Contract) Proxy.newProxyInstance(
                            Contract.class.getClassLoader(), new Class<?>[] {Contract.class},
                            (proxy, method, args) -> {
                                String nome = method.getName();
                                if (nome.equals("submitTransaction") || nome.equals("evaluateTransaction")) {
                                    List<String> chamada = new ArrayList<>();
                                    chamada.add(nome.startsWith("submit") ? "submit" : "evaluate");
                                    chamada.add((String) args[0]);
                                    if (args.length > 1) {
                                        chamada.addAll(Arrays.asList((String[]) args[1]));
                                    }
                                    CHAMADAS.add(chamada);
                                    return JSON;
                                }
                                throw new UnsupportedOperationException(nome);
                            });
                }
            };
        }
    }

    @BeforeEach
    void limpar() {
        CHAMADAS.clear();
        ORGS.clear();
    }

    private static void esperaSubmit(String... esperado) {
        assertTrue(!CHAMADAS.isEmpty(), "nenhuma chamada ao contrato");
        List<String> ultima = CHAMADAS.get(CHAMADAS.size() - 1);
        List<String> exp = new ArrayList<>();
        exp.add("submit");
        exp.addAll(Arrays.asList(esperado));
        assertEquals(exp, ultima);
    }

    private static void esperaEvaluate(String... esperado) {
        assertTrue(!CHAMADAS.isEmpty(), "nenhuma chamada ao contrato");
        List<String> ultima = CHAMADAS.get(CHAMADAS.size() - 1);
        List<String> exp = new ArrayList<>();
        exp.add("evaluate");
        exp.addAll(Arrays.asList(esperado));
        assertEquals(exp, ultima);
    }

    private static void semContato() {
        assertTrue(ORGS.isEmpty() && CHAMADAS.isEmpty(), "o peer/contrato nao deveria ser acionado: " + CHAMADAS);
    }

    // ---- listarCreditosPorProjeto(ctx, idProjeto, pageSize, bookmark) ----

    @Test
    void listarCreditosRepassaPaginacaoNaOrdemDoContrato() throws Exception {
        mvc.perform(get("/api/v1/projetos/p1/creditos").param("pageSize", "25").param("bookmark", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(JSON));
        esperaEvaluate("listarCreditosPorProjeto", "p1", "25", "abc");
    }

    @Test
    void listarCreditosSemBookmarkEnviaStringVazia() throws Exception {
        mvc.perform(get("/api/v1/projetos/p1/creditos").param("pageSize", "100"))
                .andExpect(status().isOk());
        esperaEvaluate("listarCreditosPorProjeto", "p1", "100", "");
    }

    @Test
    void listarCreditosRecusaPageSizeForaDoLimiteSemChamarOPeer() throws Exception {
        for (String ps : new String[] {"0", "-1", "101", "100000"}) {
            mvc.perform(get("/api/v1/projetos/p1/creditos").param("pageSize", ps))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.erro").value("Requisicao invalida"))
                    .andExpect(jsonPath("$.detalhe").value(org.hamcrest.Matchers.containsString("entre 1 e 100")));
        }
        semContato();
    }

    @Test
    void listarCreditosExigePageSizeEOTipoInteiro() throws Exception {
        mvc.perform(get("/api/v1/projetos/p1/creditos")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/projetos/p1/creditos").param("pageSize", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/projetos/p1/creditos").param("pageSize", "1.5")).andExpect(status().isBadRequest());
        semContato();
    }

    @Test
    void listarCreditosRecusaBookmarkGrande() throws Exception {
        mvc.perform(get("/api/v1/projetos/p1/creditos").param("pageSize", "10").param("bookmark", "x".repeat(2049)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalhe").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("xxxx"))));
        semContato();
    }

    // ---- historicoCredito(ctx, idCredito, offset, pageSize) ----

    @Test
    void historicoRepassaOffsetEPageSizeNaOrdemDoContrato() throws Exception {
        mvc.perform(get("/api/v1/creditos/c1/historico").param("offset", "20").param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(JSON));
        esperaEvaluate("historicoCredito", "c1", "20", "10");
    }

    @Test
    void historicoOffsetPadraoZero() throws Exception {
        mvc.perform(get("/api/v1/creditos/c1/historico").param("pageSize", "5")).andExpect(status().isOk());
        esperaEvaluate("historicoCredito", "c1", "0", "5");
    }

    @Test
    void historicoRecusaOffsetNegativoEPageSizeForaDoLimite() throws Exception {
        mvc.perform(get("/api/v1/creditos/c1/historico").param("offset", "-1").param("pageSize", "10"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/creditos/c1/historico").param("offset", "0").param("pageSize", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/creditos/c1/historico").param("offset", "0").param("pageSize", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/creditos/c1/historico").param("offset", "0")).andExpect(status().isBadRequest());
        semContato();
    }

    // ---- aposentarCredito(ctx, idCredito, motivo, beneficiario) ----

    @Test
    void aposentarEnviaIdMotivoEBeneficiarioNaOrdemDoContrato() throws Exception {
        mvc.perform(post("/api/v1/creditos/c1/aposentar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"compensacao 2025\",\"beneficiario\":\"" + DIGEST + "\"}"))
                .andExpect(status().isOk());
        esperaSubmit("aposentarCredito", "c1", "compensacao 2025", DIGEST);
    }

    @Test
    void aposentarRecusaBeneficiarioInvalidoSemEcoarOValorENemChamarOPeer() throws Exception {
        String pessoal = "Maria da Silva 123.456.789-09";
        mvc.perform(post("/api/v1/creditos/c1/aposentar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"m\",\"beneficiario\":\"" + pessoal + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Payload invalido"))
                .andExpect(jsonPath("$.detalhe").value(org.hamcrest.Matchers.containsString("beneficiario")))
                .andExpect(jsonPath("$.detalhe").value(org.hamcrest.Matchers.containsString("sha256:")))
                .andExpect(jsonPath("$.detalhe").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Maria"))));
        semContato();
    }

    @Test
    void aposentarRecusaBeneficiarioAusente() throws Exception {
        mvc.perform(post("/api/v1/creditos/c1/aposentar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalhe").value(org.hamcrest.Matchers.containsString("beneficiario")));
        semContato();
    }

    // ---- verificarRelatorioMRV(ctx, id, aprovado, toneladasVerificadas, justificativa) ----

    @Test
    void verificarEnviaToneladasVerificadasNaOrdemDoContrato() throws Exception {
        mvc.perform(post("/api/v1/mrv/r1/verificar").header("X-Org", "verifier")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aprovado\":true,\"toneladasVerificadas\":1500,\"justificativa\":\"conforme\"}"))
                .andExpect(status().isOk());
        esperaSubmit("verificarRelatorioMRV", "r1", "true", "1500", "conforme");
        assertEquals(List.of(Organizacao.VERIFIER), ORGS);
    }

    @Test
    void verificarRejeicaoEnviaZero() throws Exception {
        mvc.perform(post("/api/v1/mrv/r1/verificar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aprovado\":false,\"toneladasVerificadas\":0,\"justificativa\":\"evidencia insuficiente\"}"))
                .andExpect(status().isOk());
        esperaSubmit("verificarRelatorioMRV", "r1", "false", "0", "evidencia insuficiente");
    }

    @Test
    void verificarExigeToneladasVerificadas() throws Exception {
        mvc.perform(post("/api/v1/mrv/r1/verificar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aprovado\":true,\"justificativa\":\"conforme\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalhe").value(org.hamcrest.Matchers.containsString("toneladasVerificadas")));
        semContato();
    }

    @Test
    void verificarRecusaToneladasIncoerentesComAprovacao() throws Exception {
        mvc.perform(post("/api/v1/mrv/r1/verificar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aprovado\":true,\"toneladasVerificadas\":0,\"justificativa\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/mrv/r1/verificar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aprovado\":false,\"toneladasVerificadas\":7,\"justificativa\":\"x\"}"))
                .andExpect(status().isBadRequest());
        semContato();
    }

    @Test
    void toneladasFracionariasENotacaoCientificaSaoRecusadasSemTruncar() throws Exception {
        for (String valor : new String[] {"1.5", "1500.0", "1e3"}) {
            mvc.perform(post("/api/v1/mrv/r1/verificar").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"aprovado\":true,\"toneladasVerificadas\":" + valor + ",\"justificativa\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.erro").value("Payload invalido"));
            mvc.perform(post("/api/v1/mrv/r1/emitir-credito").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"idCredito\":\"c1\",\"toneladasCO2e\":" + valor + "}"))
                    .andExpect(status().isBadRequest());
        }
        semContato();
    }

    // ---- demais assinaturas ----

    @Test
    void submeterRelatorioMrvEnviaArgumentosNaOrdemDoContrato() throws Exception {
        mvc.perform(post("/api/v1/projetos/p1/mrv").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idRelatorio\":\"r1\",\"periodoInicioIso\":\"2024-01-01T00:00:00Z\","
                                + "\"periodoFimIso\":\"2024-12-31T00:00:00Z\",\"toneladasCO2eEstimadas\":2000,"
                                + "\"hashEvidencia\":\"h1\"}"))
                .andExpect(status().isCreated());
        esperaSubmit("submeterRelatorioMRV", "r1", "p1",
                "2024-01-01T00:00:00Z", "2024-12-31T00:00:00Z", "2000", "h1");
    }

    @Test
    void submeterRelatorioMrvNaoAceitaIdProjetoNemMspDoCorpo() throws Exception {
        mvc.perform(post("/api/v1/projetos/p1/mrv").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idRelatorio\":\"r1\",\"idProjeto\":\"outro\",\"mspId\":\"VerifierMSP\","
                                + "\"periodoInicioIso\":\"2024-01-01T00:00:00Z\","
                                + "\"periodoFimIso\":\"2024-12-31T00:00:00Z\",\"toneladasCO2eEstimadas\":2000,"
                                + "\"hashEvidencia\":\"h1\"}"))
                .andExpect(status().isCreated());
        // idProjeto vem do path; campos extras de identidade sao ignorados
        esperaSubmit("submeterRelatorioMRV", "r1", "p1",
                "2024-01-01T00:00:00Z", "2024-12-31T00:00:00Z", "2000", "h1");
        assertEquals(List.of(Organizacao.DEVELOPER), ORGS);
    }

    @Test
    void emitirCreditoEnviaToneladasLongInteiro() throws Exception {
        mvc.perform(post("/api/v1/mrv/r1/emitir-credito").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idCredito\":\"c1\",\"toneladasCO2e\":9007199254740993}"))
                .andExpect(status().isCreated());
        // acima de 2^53: com double haveria perda de precisao
        esperaSubmit("emitirCredito", "c1", "r1", "9007199254740993");
    }

    @Test
    void emitirCreditoRecusaZeroNegativoEAusente() throws Exception {
        for (String corpo : new String[] {"{\"idCredito\":\"c1\",\"toneladasCO2e\":0}",
                "{\"idCredito\":\"c1\",\"toneladasCO2e\":-1}", "{\"idCredito\":\"c1\"}"}) {
            mvc.perform(post("/api/v1/mrv/r1/emitir-credito").contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
        semContato();
    }

    @Test
    void registrarProjetoEnviaArgumentosNaOrdemDoContrato() throws Exception {
        mvc.perform(post("/api/v1/projetos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"p1\",\"nome\":\"Projeto\",\"areaHectares\":10.5,\"metodologia\":\"VM0015\"}"))
                .andExpect(status().isCreated());
        esperaSubmit("registrarProjeto", "p1", "Projeto", "10.5", "VM0015");
    }

    @Test
    void transferirEConsultasEnviamArgumentos() throws Exception {
        mvc.perform(post("/api/v1/creditos/c1/transferir").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"novoProprietarioMspId\":\"MarketplaceMSP\"}"))
                .andExpect(status().isOk());
        esperaSubmit("transferirCredito", "c1", "MarketplaceMSP");

        mvc.perform(get("/api/v1/creditos/c1")).andExpect(status().isOk());
        esperaEvaluate("consultarCredito", "c1");
        mvc.perform(get("/api/v1/projetos/p1")).andExpect(status().isOk());
        esperaEvaluate("consultarProjeto", "p1");
    }

    @Test
    void organizacaoInvalidaNoHeaderERecusadaSemChamarOPeer() throws Exception {
        mvc.perform(get("/api/v1/creditos/c1").header("X-Org", "outra"))
                .andExpect(status().isBadRequest());
        semContato();
    }
}
