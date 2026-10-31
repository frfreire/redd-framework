package br.com.redd.chaincode;

import br.com.redd.chaincode.model.CreditoCarbono;
import br.com.redd.chaincode.model.ProjetoREDD;
import br.com.redd.chaincode.model.RelatorioMRV;
import br.com.redd.chaincode.model.StatusCredito;
import br.com.redd.chaincode.model.StatusProjeto;
import br.com.redd.chaincode.model.StatusRelatorioMRV;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hyperledger.fabric.contract.ClientIdentity;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes unitarios do {@link RedContract}, com {@link ChaincodeStub} e
 * {@link ClientIdentity} mockados via Mockito -- sem subir rede Fabric real.
 *
 * <p>O estado do ledger e simulado por um {@link Map} em memoria dentro do
 * proprio mock de {@link ChaincodeStub} (getStringState/putStringState).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedContractTest {

    private static final String DEVELOPER_MSP = "DeveloperMSP";
    private static final String VERIFIER_MSP = "VerifierMSP";
    private static final String MARKETPLACE_MSP = "MarketplaceMSP";

    @Mock
    private Context ctx;

    @Mock
    private ChaincodeStub stub;

    @Mock
    private ClientIdentity clientIdentity;

    private final Map<String, String> ledger = new HashMap<>();
    private final ObjectMapper mapper = buildMapper();
    private RedContract contract;

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    @BeforeEach
    void setUp() {
        contract = new RedContract();
        ledger.clear();

        when(ctx.getStub()).thenReturn(stub);
        when(ctx.getClientIdentity()).thenReturn(clientIdentity);
        lenient().when(clientIdentity.getId()).thenReturn(AUTOR_ID);

        lenient().when(stub.getStringState(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return ledger.getOrDefault(key, "");
        });
        lenient().doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            ledger.put(key, value);
            return null;
        }).when(stub).putStringState(anyString(), anyString());
        lenient().when(stub.getStateByPartialCompositeKey(anyString())).thenAnswer(inv -> {
            String prefixo = inv.getArgument(0);
            List<KeyValue> itens = new ArrayList<>();
            for (Map.Entry<String, String> e : new TreeMap<>(ledger).entrySet()) {
                if (e.getKey().startsWith(prefixo)) {
                    KeyValue kv = mock(KeyValue.class);
                    lenient().when(kv.getKey()).thenReturn(e.getKey());
                    lenient().when(kv.getStringValue()).thenReturn(e.getValue());
                    itens.add(kv);
                }
            }
            @SuppressWarnings("unchecked")
            QueryResultsIterator<KeyValue> it = mock(QueryResultsIterator.class);
            lenient().when(it.iterator()).thenAnswer(x -> itens.iterator());
            return it;
        });
        lenient().when(stub.getTxTimestamp()).thenReturn(Instant.parse("2024-01-01T00:00:00Z"));
        lenient().doNothing().when(stub).setEvent(anyString(), any(byte[].class));
    }

    private void identidade(String mspId) {
        when(clientIdentity.getMSPID()).thenReturn(mspId);
    }

    private void semearProjeto(String id, StatusProjeto status) throws Exception {
        semearProjeto(id, status, DEVELOPER_MSP);
    }

    private void semearProjeto(String id, StatusProjeto status, String proponenteMspId) throws Exception {
        ProjetoREDD projeto = new ProjetoREDD(id, "Projeto Teste", 100.0, proponenteMspId, "VCS VM0007", status);
        ledger.put("PROJETO_" + id, mapper.writeValueAsString(projeto));
    }

    private void semearRelatorio(String id, String idProjeto, StatusRelatorioMRV status) throws Exception {
        semearRelatorio(id, idProjeto, status, 1000L, status == StatusRelatorioMRV.VERIFICADO ? 1000L : 0L);
    }

    private void semearRelatorio(String id, String idProjeto, StatusRelatorioMRV status,
                                 long estimadas, long verificadas) throws Exception {
        RelatorioMRV r = new RelatorioMRV(id, idProjeto, Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-02-01T00:00:00Z"), estimadas, "sha256:abc", status);
        r.setToneladasVerificadas(verificadas);
        ledger.put("RELATORIO_MRV_" + id, mapper.writeValueAsString(r));
    }

    private void semearCredito(String id, String idProjeto, String idRelatorio, String proprietario,
                                StatusCredito status) throws Exception {
        CreditoCarbono c = new CreditoCarbono(id, idProjeto, idRelatorio, 500L, proprietario, status);
        ledger.put("CREDITO_" + id, mapper.writeValueAsString(c));
    }

    // ------------------------------------------------------------------
    // registrarProjeto
    // ------------------------------------------------------------------

    @Test
    void registrarProjeto_comSucesso() {
        identidade(DEVELOPER_MSP);

        ProjetoREDD resultado = contract.registrarProjeto(ctx, "proj-1", "Projeto Amazonia", 500.0, "VCS VM0007");

        assertEquals("proj-1", resultado.getId());
        assertEquals(StatusProjeto.REGISTRADO, resultado.getStatus());
        assertEquals(DEVELOPER_MSP, resultado.getProponenteMspId());
        assertTrue(ledger.containsKey("PROJETO_proj-1"));
    }

    @Test
    void registrarProjeto_rejeitaMspDiferenteDeDeveloper() {
        identidade(VERIFIER_MSP);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.registrarProjeto(ctx, "proj-1", "Projeto X", 100.0, "VCS VM0007"));
        assertTrue(ex.getMessage().contains("DeveloperMSP"));
    }

    // ------------------------------------------------------------------
    // submeterRelatorioMRV
    // ------------------------------------------------------------------

    @Test
    void submeterRelatorioMRV_comSucesso() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        identidade(DEVELOPER_MSP);

        RelatorioMRV relatorio = contract.submeterRelatorioMRV(ctx, "mrv-1", "proj-1",
                "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z", 1000L, "sha256:abc");

        assertEquals(StatusRelatorioMRV.SUBMETIDO, relatorio.getStatus());
        assertEquals("proj-1", relatorio.getIdProjeto());

        ProjetoREDD projetoAtualizado = mapper.readValue(ledger.get("PROJETO_proj-1"), ProjetoREDD.class);
        assertEquals(StatusProjeto.EM_MONITORAMENTO, projetoAtualizado.getStatus());
    }

    @Test
    void submeterRelatorioMRV_falhaSeProjetoNaoExiste() {
        identidade(DEVELOPER_MSP);

        assertThrows(ChaincodeException.class, () -> contract.submeterRelatorioMRV(ctx, "mrv-1", "proj-inexistente",
                "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z", 1000L, "sha256:abc"));
    }

    // ------------------------------------------------------------------
    // verificarRelatorioMRV
    // ------------------------------------------------------------------

    @Test
    void verificarRelatorioMRV_rejeitaQuandoChamadorNaoEhVerifier() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO);

        // A separacao entre proponente e verificador e garantida
        // estruturalmente pelo gate de papel desta funcao: apenas
        // VerifierMSP pode chamar verificarRelatorioMRV. Qualquer outro MSP
        // (incluindo o proprio DeveloperMSP, proponente do projeto) e
        // rejeitado aqui, antes de qualquer logica de negocio.
        identidade(DEVELOPER_MSP);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.verificarRelatorioMRV(ctx, "mrv-1", true, 600L, "Aprovado apos auditoria"));
        assertTrue(ex.getMessage().contains(VERIFIER_MSP));
    }

    @Test
    void verificarRelatorioMRV_comSucessoQuandoMspDiferente() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO);

        identidade(VERIFIER_MSP);

        RelatorioMRV relatorio = contract.verificarRelatorioMRV(ctx, "mrv-1", true, 600L, "Aprovado apos auditoria");

        assertEquals(StatusRelatorioMRV.VERIFICADO, relatorio.getStatus());
        assertEquals(VERIFIER_MSP, relatorio.getVerificadoPorMspId());
    }

    @Test
    void verificarRelatorioMRV_rejeitaQuandoProponenteNoLedgerCoincideComChamador() throws Exception {
        // Cenario de defesa em profundidade: o dado persistido no ledger diz
        // que o proponente do projeto e VerifierMSP (por exemplo, apos uma
        // migracao de dados, correcao manual, ou evolucao futura do
        // contrato que permita outras topologias de papel). O gate de papel
        // (apenas VerifierMSP pode chamar) e satisfeito, mas a checagem
        // dinamica contra projeto.getProponenteMspId() deve rejeitar mesmo
        // assim, pois o chamador e o mesmo MSP que consta como proponente.
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO, VERIFIER_MSP);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO);

        identidade(VERIFIER_MSP);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.verificarRelatorioMRV(ctx, "mrv-1", true, 600L, "Aprovado apos auditoria"));
        assertTrue(ex.getMessage().toLowerCase().contains("conflito"));
    }

    // ------------------------------------------------------------------
    // emitirCredito
    // ------------------------------------------------------------------

    @Test
    void emitirCredito_falhaSeRelatorioNaoVerificado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO);
        identidade(DEVELOPER_MSP);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.emitirCredito(ctx, "cred-1", "mrv-1", 500L));
        assertTrue(ex.getMessage().contains("VERIFICADO"));
    }

    @Test
    void emitirCredito_comSucessoQuandoRelatorioVerificado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO);
        identidade(DEVELOPER_MSP);

        CreditoCarbono credito = contract.emitirCredito(ctx, "cred-1", "mrv-1", 500L);

        assertEquals(StatusCredito.EMITIDO, credito.getStatus());
        assertEquals(DEVELOPER_MSP, credito.getProprietarioAtual());
        assertEquals(1, credito.getHistorico().size());
    }

    // ------------------------------------------------------------------
    // transferirCredito
    // ------------------------------------------------------------------

    @Test
    void transferirCredito_falhaSeChamadorNaoEProprietario() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", DEVELOPER_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.transferirCredito(ctx, "cred-1", VERIFIER_MSP));
        assertTrue(ex.getMessage().toLowerCase().contains("proprietario"));
    }

    @Test
    void transferirCredito_comSucessoQuandoChamadorEProprietario() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", DEVELOPER_MSP, StatusCredito.EMITIDO);
        identidade(DEVELOPER_MSP);

        CreditoCarbono credito = contract.transferirCredito(ctx, "cred-1", MARKETPLACE_MSP);

        assertEquals(MARKETPLACE_MSP, credito.getProprietarioAtual());
        assertEquals(StatusCredito.TRANSFERIDO, credito.getStatus());
    }

    // ------------------------------------------------------------------
    // aposentarCredito
    // ------------------------------------------------------------------

    private static final String BENEF = "sha256:" + "a1b2c3d4".repeat(8);
    private static final String BENEF2 = "sha256:" + "0f".repeat(32);
    private static final String AUTOR_ID = "x509::CN=marketplace-user1,OU=client::CN=ca.marketplace";

    private CreditoCarbono creditoGravado(String id) throws Exception {
        return mapper.readValue(ledger.get("CREDITO_" + id), CreditoCarbono.class);
    }

    private org.mockito.ArgumentCaptor<byte[]> capturarEvento(String nome) {
        org.mockito.ArgumentCaptor<byte[]> payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.Mockito.verify(stub).setEvent(org.mockito.ArgumentMatchers.eq(nome), payload.capture());
        return payload;
    }

    private void exigirAposentadoriaRejeitada(String id, String motivo, String beneficiario) {
        Map<String, String> antes = new HashMap<>(ledger);
        org.mockito.Mockito.clearInvocations(stub);
        assertThrows(ChaincodeException.class, () -> contract.aposentarCredito(ctx, id, motivo, beneficiario));
        assertEquals(antes, ledger, "estado nao pode mudar em falha");
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.never()).setEvent(anyString(), any(byte[].class));
    }

    @Test
    void aposentarCredito_comSucessoGravaAutorMotivoBeneficiarioETimestampDoStub() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);
        Instant instanteDaTx = Instant.parse("2025-03-04T05:06:07Z");
        when(stub.getTxTimestamp()).thenReturn(instanteDaTx);

        CreditoCarbono r = contract.aposentarCredito(ctx, "cred-1", "Compensacao voluntaria 2024", BENEF);

        assertEquals(StatusCredito.APOSENTADO, r.getStatus());
        CreditoCarbono g = creditoGravado("cred-1");
        assertEquals(StatusCredito.APOSENTADO, g.getStatus());
        assertEquals("Compensacao voluntaria 2024", g.getMotivoAposentadoria());
        assertEquals(BENEF, g.getBeneficiarioAposentadoria());
        assertEquals(MARKETPLACE_MSP, g.getAposentadoPorMspId());
        assertEquals(RedContract.referenciaOpacaDoAutor(MARKETPLACE_MSP, AUTOR_ID), g.getAposentadoPorId());
        assertEquals(instanteDaTx, g.getAposentadoEm());
        // aposentadoria e do credito inteiro: identidade e quantidade preservadas
        assertEquals(500L, g.getToneladasCO2e());
        assertEquals(MARKETPLACE_MSP, g.getProprietarioAtual());
        assertEquals(1, g.getHistorico().size());
        assertEquals("APOSENTADORIA", g.getHistorico().get(0).getTipoEvento());
        assertEquals(instanteDaTx, g.getHistorico().get(0).getTimestamp());
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.atLeastOnce()).getTxTimestamp();
    }

    @Test
    void aposentarCredito_developerProprietarioInicialTambemPodeAposentar() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", DEVELOPER_MSP, StatusCredito.EMITIDO);
        identidade(DEVELOPER_MSP);

        assertEquals(StatusCredito.APOSENTADO,
                contract.aposentarCredito(ctx, "cred-1", "uso proprio", BENEF).getStatus());
    }

    @Test
    void aposentarCredito_aposTransferenciaSoOProprietarioNovoPode() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", DEVELOPER_MSP, StatusCredito.EMITIDO);
        identidade(DEVELOPER_MSP);
        contract.transferirCredito(ctx, "cred-1", MARKETPLACE_MSP);

        // o MSP do proponente original perde a autoridade (INV-06)
        exigirAposentadoriaRejeitada("cred-1", "motivo", BENEF);
        identidade(MARKETPLACE_MSP);
        assertEquals(StatusCredito.APOSENTADO,
                contract.aposentarCredito(ctx, "cred-1", "motivo", BENEF).getStatus());
    }

    @Test
    void aposentarCredito_naoProprietarioFalhaSemAlterarEstado() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(DEVELOPER_MSP);
        Map<String, String> antes = new HashMap<>(ledger);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.aposentarCredito(ctx, "cred-1", "Tentativa invalida", BENEF));

        assertTrue(ex.getMessage().toLowerCase().contains("proprietario"));
        assertEquals(antes, ledger);
        assertEquals(StatusCredito.EMITIDO, creditoGravado("cred-1").getStatus());
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.never()).setEvent(anyString(), any(byte[].class));
    }

    @Test
    void aposentarCredito_creditoInexistenteFalha() {
        identidade(MARKETPLACE_MSP);

        exigirAposentadoriaRejeitada("nao-existe", "motivo", BENEF);
    }

    @Test
    void aposentarCredito_segundaTentativaFalhaEEstadoFicaInalterado() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);
        when(stub.getTxTimestamp()).thenReturn(Instant.parse("2024-06-01T00:00:00Z"));
        contract.aposentarCredito(ctx, "cred-1", "Compensacao voluntaria 2024", BENEF);
        Map<String, String> antes = new HashMap<>(ledger);
        when(stub.getTxTimestamp()).thenReturn(Instant.parse("2024-07-01T00:00:00Z"));

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.aposentarCredito(ctx, "cred-1", "Nova tentativa", BENEF2));

        assertTrue(ex.getMessage().contains("APOSENTADO"));
        assertEquals(antes, ledger);
        CreditoCarbono g = creditoGravado("cred-1");
        assertEquals("Compensacao voluntaria 2024", g.getMotivoAposentadoria());
        assertEquals(BENEF, g.getBeneficiarioAposentadoria());
        assertEquals(Instant.parse("2024-06-01T00:00:00Z"), g.getAposentadoEm());
        assertEquals(1, g.getHistorico().size());
    }

    @Test
    void aposentarCredito_aposentadoNaoChegaAOutroEstado_transferirFalha() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);
        contract.aposentarCredito(ctx, "cred-1", "motivo", BENEF);
        Map<String, String> antes = new HashMap<>(ledger);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.transferirCredito(ctx, "cred-1", DEVELOPER_MSP));

        assertTrue(ex.getMessage().contains("APOSENTADO"));
        assertEquals(antes, ledger);
        assertEquals(StatusCredito.APOSENTADO, creditoGravado("cred-1").getStatus());
        assertEquals(MARKETPLACE_MSP, creditoGravado("cred-1").getProprietarioAtual());
    }

    @Test
    void aposentarCredito_aposentadoSemeadoPeloLedgerTambemNaoTransfere() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.APOSENTADO);
        identidade(MARKETPLACE_MSP);

        assertThrows(ChaincodeException.class, () -> contract.transferirCredito(ctx, "cred-1", DEVELOPER_MSP));
        assertThrows(ChaincodeException.class, () -> contract.aposentarCredito(ctx, "cred-1", "m", BENEF));
    }

    @Test
    void aposentarCredito_motivoOuBeneficiarioVazioNuloOuEmBrancoFalha() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        for (String ruim : new String[] {null, "", "   ", "\t\n "}) {
            exigirAposentadoriaRejeitada("cred-1", ruim, BENEF);
            exigirAposentadoriaRejeitada("cred-1", "motivo", ruim);
            exigirAposentadoriaRejeitada(ruim, "motivo", BENEF);
        }
        assertEquals(StatusCredito.EMITIDO, creditoGravado("cred-1").getStatus());
    }

    @Test
    void aposentarCredito_caractereDeControleNosCamposRejeitado() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        String[] ruins = {"a\u0000b", "a\nb", "a\tb", "a\rb", "a\u001Fb", "a\u007Fb", "a\u0085b",
                "a\u2028b", "a\u2029b", "a\u200Bb", "a\u202Eb", "a\uDBFF\uDFFFb"};
        for (String ruim : ruins) {
            exigirAposentadoriaRejeitada("cred-1", ruim, BENEF);
            exigirAposentadoriaRejeitada("cred-1", "motivo", ruim);
            exigirAposentadoriaRejeitada(ruim, "motivo", BENEF);
        }
    }

    @Test
    void aposentarCredito_idComEspacoNasPontasNaoEhNormalizadoERejeitado() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        exigirAposentadoriaRejeitada(" cred-1", "motivo", BENEF);
        exigirAposentadoriaRejeitada("cred-1 ", "motivo", BENEF);
    }

    @Test
    void aposentarCredito_tamanhoAcimaDoLimiteRejeitadoEExatamenteNoLimitePassa() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        exigirAposentadoriaRejeitada("cred-1", "m".repeat(257), BENEF);
        exigirAposentadoriaRejeitada("cred-1", "motivo", "sha256:" + "b".repeat(65));
        exigirAposentadoriaRejeitada("cred-1", "motivo", "b".repeat(1000));
        exigirAposentadoriaRejeitada("c".repeat(129), "motivo", BENEF);

        CreditoCarbono r = contract.aposentarCredito(ctx, "cred-1", "m".repeat(256), BENEF);
        assertEquals(256, r.getMotivoAposentadoria().length());
        assertEquals(71, r.getBeneficiarioAposentadoria().length());
    }

    @Test
    void aposentarCredito_beneficiarioDigestSha256ValidoPassa() throws Exception {
        identidade(MARKETPLACE_MSP);
        String sha = "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
        String soDigitos = "sha256:" + "0123456789".repeat(6) + "0123"; // so digitos 0-9 tambem e hex valido
        String[] validos = {sha, BENEF, BENEF2, soDigitos};
        int i = 0;
        for (String ok : validos) {
            String id = "cred-ok-" + (i++);
            semearCredito(id, "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
            CreditoCarbono r = contract.aposentarCredito(ctx, id, "compensacao-2025", ok);
            assertEquals(ok, r.getBeneficiarioAposentadoria());
            assertEquals(ok, creditoGravado(id).getBeneficiarioAposentadoria());
        }
    }

    @Test
    void aposentarCredito_beneficiarioForaDoFormatoDigestRejeitado() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        String hex64 = "a".repeat(64);
        String[] ruins = {
                // o caso do critic e variantes: identificador direto misturado a letras
                "CPF12345678909", "CPF-123.456.789-09", "cpf:12345678909", "sha256:CPF12345678909",
                // rotulos livres e legiveis
                "ORG-123:ref-9", "benef-ref-001", "benef.ref_01", "A", "x1234567890", "ref:9f86d081",
                "Maria Silva", "Jo\u00e3o", "joao@exemplo.com", "a@b", "@",
                // numeros pessoais puros
                "123.456.789-09", "12345678909", "12345678000195", "(11) 98765-4321", "+55 11 98765-4321",
                // hash sem prefixo, prefixo errado, maiusculas
                hex64, "sha1:" + hex64, "SHA256:" + hex64, "Sha256:" + hex64, "sha256-" + hex64, "sha256:" + hex64.toUpperCase(),
                "sha256:" + "a".repeat(63) + "A", "sha256:" + "a".repeat(63) + "g", "sha256:" + "a".repeat(63) + "z",
                // comprimento errado
                "sha256:", "sha256:" + "a".repeat(63), "sha256:" + "a".repeat(65), "sha256:" + "a".repeat(128),
                "sha512:" + "a".repeat(128),
                // espaco, controle, unicode
                " sha256:" + hex64, "sha256:" + hex64 + " ", "sha256: " + hex64.substring(1), "sha256:" + hex64 + "\n",
                "sha256:" + hex64.substring(1) + "\u0661", "sha256:" + hex64.substring(1) + "\uFF11",
                "sha256:" + "a".repeat(32) + "\u00e9" + "a".repeat(31)
        };
        for (String ruim : ruins) {
            exigirAposentadoriaRejeitada("cred-1", "motivo", ruim);
        }
        assertEquals(StatusCredito.EMITIDO, creditoGravado("cred-1").getStatus());
        assertEquals(null, creditoGravado("cred-1").getBeneficiarioAposentadoria());
    }

    @Test
    void aposentarCredito_beneficiarioRejeitadoNaoApareceNoEstadoNemNoEventoNemNaMensagem() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        for (String ruim : new String[] {"CPF12345678909", "123.456.789-09", "joao@exemplo.com", "Maria Silva"}) {
            exigirAposentadoriaRejeitada("cred-1", "motivo", ruim);
            for (String json : ledger.values()) {
                assertFalse(json.contains(ruim), "valor rejeitado nao pode estar no estado");
            }
            ChaincodeException ex = assertThrows(ChaincodeException.class,
                    () -> contract.aposentarCredito(ctx, "cred-1", "motivo", ruim));
            assertFalse(ex.getMessage().contains(ruim), "mensagem de erro nao repete o valor recebido");
            assertTrue(ex.getMessage().contains("sha256:"), "mensagem informa o formato esperado");
        }
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.never()).setEvent(anyString(), any(byte[].class));
    }

    @Test
    void aposentarCredito_motivoComIdentificadorDiretoRejeitadoELegitimoPassa() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        String[] ruins = {
                "contato joao@exemplo.com", "CPF 123.456.789-09", "doc 12345678909",
                "CNPJ 12.345.678/0001-95", "ligar (11) 98765-4321", "tel 98765-4321", "+55 11 98765-4321", "protocolo 12345678"
        };
        for (String ruim : ruins) {
            exigirAposentadoriaRejeitada("cred-1", ruim, BENEF);
        }

        String[] legitimos = {"compensacao-2025", "Compensacao voluntaria 2024", "CORSIA fase 1, 2024-2026",
                "lote 2025-01-15", "uso proprio", "m".repeat(256)};
        int i = 0;
        for (String ok : legitimos) {
            String id = "cred-m-" + (i++);
            semearCredito(id, "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
            assertEquals(ok, contract.aposentarCredito(ctx, id, ok, BENEF).getMotivoAposentadoria());
        }
    }

    @Test
    void aposentarCredito_emiteCreditoAposentadoUmaVezComOsCamposRelevantes() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);
        Instant instanteDaTx = Instant.parse("2025-03-04T05:06:07Z");
        when(stub.getTxTimestamp()).thenReturn(instanteDaTx);

        contract.aposentarCredito(ctx, "cred-1", "Compensacao 2024", BENEF);

        org.mockito.Mockito.verify(stub, org.mockito.Mockito.times(1)).setEvent(anyString(), any(byte[].class));
        com.fasterxml.jackson.databind.JsonNode no = mapper.readTree(capturarEvento("CreditoAposentado").getValue());
        assertEquals("cred-1", no.get("id").asText());
        assertEquals("proj-1", no.get("idProjeto").asText());
        assertEquals("APOSENTADO", no.get("status").asText());
        assertEquals(500L, no.get("toneladasCO2e").asLong());
        assertEquals("Compensacao 2024", no.get("motivoAposentadoria").asText());
        assertEquals(BENEF, no.get("beneficiarioAposentadoria").asText());
        assertEquals(MARKETPLACE_MSP, no.get("aposentadoPorMspId").asText());
        assertEquals(RedContract.referenciaOpacaDoAutor(MARKETPLACE_MSP, AUTOR_ID), no.get("aposentadoPorId").asText());
        assertEquals(instanteDaTx, Instant.parse(no.get("aposentadoEm").asText()));
    }

    @Test
    void aposentarCredito_dnECnNaoAparecemNoEstadoNemNoEventoEReferenciaEDeterministica() throws Exception {
        String dn = "x509::CN=Maria da Silva,OU=client,O=Marketplace::CN=ca.marketplace";
        String idBruto = java.util.Base64.getEncoder().encodeToString(dn.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(clientIdentity.getId()).thenReturn(idBruto);
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);

        contract.aposentarCredito(ctx, "cred-1", "motivo", BENEF);

        String estado = ledger.get(ledger.keySet().stream().filter(k -> k.contains("cred-1")).findFirst().get());
        String evento = new String(capturarEvento("CreditoAposentado").getValue(), java.nio.charset.StandardCharsets.UTF_8);
        for (String texto : new String[] {estado, evento}) {
            assertFalse(texto.contains(idBruto), "id bruto vazou");
            assertFalse(texto.contains(dn), "DN vazou");
            assertFalse(texto.contains("Maria"), "CN vazou");
            assertFalse(texto.contains("CN="), "CN vazou");
        }
        String ref = creditoGravado("cred-1").getAposentadoPorId();
        assertTrue(ref.matches("sha256:[0-9a-f]{64}"));
        assertEquals(ref, RedContract.referenciaOpacaDoAutor(MARKETPLACE_MSP, idBruto));
        assertNotEquals(ref, RedContract.referenciaOpacaDoAutor(VERIFIER_MSP, idBruto));
        assertNotEquals(ref, RedContract.referenciaOpacaDoAutor(MARKETPLACE_MSP, idBruto + "x"));
    }

    @Test
    void aposentarCredito_identidadeVemDoClientIdentityNaoDeArgumento() throws Exception {
        // Assinatura fechada: (Context, idCredito, motivo, beneficiario). Nao ha
        // parametro de MSP/papel/autor (nomes de parametro nao estao no bytecode
        // sem -parameters, entao a checagem e por tipo e quantidade).
        java.lang.reflect.Method m = RedContract.class.getMethod("aposentarCredito",
                org.hyperledger.fabric.contract.Context.class, String.class, String.class, String.class);
        assertEquals(4, m.getParameterCount());
        assertEquals(1, java.util.Arrays.stream(RedContract.class.getDeclaredMethods())
                .filter(x -> x.getName().equals("aposentarCredito")).count());
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);
        contract.aposentarCredito(ctx, "cred-1", "motivo", BENEF);
        org.mockito.Mockito.verify(clientIdentity).getMSPID();
        org.mockito.Mockito.verify(clientIdentity).getId();
    }

    @Test
    void aposentarCredito_mesmaEntradaEmDoisLedgersProduzMesmoEstadoEEvento() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);
        Map<String, String> inicial = new HashMap<>(ledger);
        org.mockito.ArgumentCaptor<byte[]> ev = org.mockito.ArgumentCaptor.forClass(byte[].class);

        contract.aposentarCredito(ctx, "cred-1", "motivo", BENEF);
        Map<String, String> primeiro = new HashMap<>(ledger);
        ledger.clear();
        ledger.putAll(inicial);
        contract.aposentarCredito(ctx, "cred-1", "motivo", BENEF);

        assertEquals(primeiro, ledger);
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.times(2))
                .setEvent(org.mockito.ArgumentMatchers.eq("CreditoAposentado"), ev.capture());
        assertTrue(java.util.Arrays.equals(ev.getAllValues().get(0), ev.getAllValues().get(1)));
    }

    @Test
    void aposentarCredito_naoUsaRelogioDaMaquina() throws Exception {
        semearCredito("cred-1", "proj-1", "mrv-1", MARKETPLACE_MSP, StatusCredito.EMITIDO);
        identidade(MARKETPLACE_MSP);
        when(stub.getTxTimestamp()).thenReturn(Instant.parse("1999-12-31T23:59:59Z"));

        CreditoCarbono r = contract.aposentarCredito(ctx, "cred-1", "motivo", BENEF);

        assertEquals(Instant.parse("1999-12-31T23:59:59Z"), r.getAposentadoEm());
    }

    // ------------------------------------------------------------------
    // INV-12: estimado e verificado sao grandezas separadas
    // ------------------------------------------------------------------

    @Test
    void inv12_emitirAcimaDoVerificadoFalha_mesmoAbaixoDoEstimado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.emitirCredito(ctx, "cred-1", "mrv-1", 61L));
        assertTrue(ex.getMessage().contains("verificadas"));
        assertTrue(!ledger.containsKey("CREDITO_cred-1"));
    }

    @Test
    void inv12_emitirExatamenteOVerificadoPassa() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);

        CreditoCarbono credito = contract.emitirCredito(ctx, "cred-1", "mrv-1", 60L);

        assertEquals(60L, credito.getToneladasCO2e());
        assertTrue(ledger.containsKey("CREDITO_cred-1"));
    }

    @Test
    void inv12_verificadoPodeExcederOEstimado_semTeto() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 150L);
        identidade(DEVELOPER_MSP);

        assertEquals(150L, contract.emitirCredito(ctx, "cred-1", "mrv-1", 150L).getToneladasCO2e());
    }

    @Test
    void inv12_emitirAntesDeVerificarFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO, 100L, 0L);
        identidade(DEVELOPER_MSP);

        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, "cred-1", "mrv-1", 1L));
        assertTrue(!ledger.containsKey("CREDITO_cred-1"));
    }

    @Test
    void inv12_emitirSobreRelatorioRejeitadoFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.REJEITADO, 100L, 0L);
        identidade(DEVELOPER_MSP);

        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, "cred-1", "mrv-1", 1L));
    }

    @Test
    void inv12_verificarGravaOVerificadoSemAlterarOEstimado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO, 100L, 0L);
        identidade(VERIFIER_MSP);

        RelatorioMRV r = contract.verificarRelatorioMRV(ctx, "mrv-1", true, 60L, "ok");

        assertEquals(60L, r.getToneladasVerificadas());
        assertEquals(100L, r.getToneladasCO2eEstimadas());
        RelatorioMRV gravado = mapper.readValue(ledger.get("RELATORIO_MRV_mrv-1"), RelatorioMRV.class);
        assertEquals(60L, gravado.getToneladasVerificadas());
        assertEquals(100L, gravado.getToneladasCO2eEstimadas());
    }

    @Test
    void inv12_developerNaoPodeVerificar() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO, 100L, 0L);
        identidade(DEVELOPER_MSP);

        assertThrows(ChaincodeException.class,
                () -> contract.verificarRelatorioMRV(ctx, "mrv-1", true, 60L, "ok"));
        RelatorioMRV gravado = mapper.readValue(ledger.get("RELATORIO_MRV_mrv-1"), RelatorioMRV.class);
        assertEquals(StatusRelatorioMRV.SUBMETIDO, gravado.getStatus());
        assertEquals(0L, gravado.getToneladasVerificadas());
    }

    @Test
    void inv12_verificadoZeroOuNegativoNaAprovacaoRejeitado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO, 100L, 0L);
        identidade(VERIFIER_MSP);

        assertThrows(ChaincodeException.class,
                () -> contract.verificarRelatorioMRV(ctx, "mrv-1", true, 0L, "ok"));
        assertThrows(ChaincodeException.class,
                () -> contract.verificarRelatorioMRV(ctx, "mrv-1", true, -5L, "ok"));
        RelatorioMRV gravado = mapper.readValue(ledger.get("RELATORIO_MRV_mrv-1"), RelatorioMRV.class);
        assertEquals(StatusRelatorioMRV.SUBMETIDO, gravado.getStatus());
    }

    @Test
    void inv12_rejeicaoNaoGravaVerificado_eExigeZero() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO, 100L, 0L);
        identidade(VERIFIER_MSP);

        assertThrows(ChaincodeException.class,
                () -> contract.verificarRelatorioMRV(ctx, "mrv-1", false, 60L, "nao conforme"));

        RelatorioMRV r = contract.verificarRelatorioMRV(ctx, "mrv-1", false, 0L, "nao conforme");
        assertEquals(StatusRelatorioMRV.REJEITADO, r.getStatus());
        assertEquals(0L, r.getToneladasVerificadas());
    }

    @Test
    void inv12_verificadoEImutavel_reverificarFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(VERIFIER_MSP);

        assertThrows(ChaincodeException.class,
                () -> contract.verificarRelatorioMRV(ctx, "mrv-1", true, 90L, "outra vez"));
        RelatorioMRV gravado = mapper.readValue(ledger.get("RELATORIO_MRV_mrv-1"), RelatorioMRV.class);
        assertEquals(60L, gravado.getToneladasVerificadas());
    }

    @Test
    void inv12_serializacaoJsonCarregaVerificadoComoNumeroInteiro() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO, 100L, 0L);
        identidade(VERIFIER_MSP);

        contract.verificarRelatorioMRV(ctx, "mrv-1", true, 60L, "ok");

        com.fasterxml.jackson.databind.JsonNode no = mapper.readTree(ledger.get("RELATORIO_MRV_mrv-1"));
        assertTrue(no.get("toneladasVerificadas").isIntegralNumber());
        assertTrue(no.get("toneladasVerificadas").canConvertToLong());
        assertEquals(60L, no.get("toneladasVerificadas").asLong());
        assertTrue(no.get("toneladasCO2eEstimadas").isIntegralNumber());
        assertEquals("60", no.get("toneladasVerificadas").toString());
    }

    @Test
    void inv12_eventoDeVerificacaoCarregaOVerificado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO, 100L, 0L);
        identidade(VERIFIER_MSP);

        contract.verificarRelatorioMRV(ctx, "mrv-1", true, 60L, "ok");

        org.mockito.ArgumentCaptor<byte[]> payload = org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.Mockito.verify(stub).setEvent(org.mockito.ArgumentMatchers.eq("RelatorioMRVVerificado"),
                payload.capture());
        com.fasterxml.jackson.databind.JsonNode no = mapper.readTree(payload.getValue());
        assertEquals(60L, no.get("toneladasVerificadas").asLong());
    }

    // ------------------------------------------------------------------
    // INV-03: conservacao por relatorio (acumulador toneladasEmitidas)
    // ------------------------------------------------------------------

    private void semearRelatorioComEmitidas(String id, String idProjeto, long verificadas, long emitidas)
            throws Exception {
        RelatorioMRV r = new RelatorioMRV(id, idProjeto, Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-02-01T00:00:00Z"), 100L, "sha256:" + id, StatusRelatorioMRV.VERIFICADO);
        r.setToneladasVerificadas(verificadas);
        r.setToneladasEmitidas(emitidas);
        ledger.put("RELATORIO_MRV_" + id, mapper.writeValueAsString(r));
    }

    private RelatorioMRV relatorioGravado(String id) throws Exception {
        return mapper.readValue(ledger.get("RELATORIO_MRV_" + id), RelatorioMRV.class);
    }

    @Test
    void inv03_duasParcelasQueSomamOVerificadoPassam_eUmAMaisFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);

        assertEquals(40L, contract.emitirCredito(ctx, "cred-1", "mrv-1", 40L).getToneladasCO2e());
        assertEquals(40L, relatorioGravado("mrv-1").getToneladasEmitidas());

        assertEquals(20L, contract.emitirCredito(ctx, "cred-2", "mrv-1", 20L).getToneladasCO2e());
        assertEquals(60L, relatorioGravado("mrv-1").getToneladasEmitidas());

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.emitirCredito(ctx, "cred-3", "mrv-1", 1L));
        assertTrue(ex.getMessage().contains("verificadas (60)"));
        assertTrue(ex.getMessage().contains("emitidas (60)"));
        assertTrue(!ledger.containsKey("CREDITO_cred-3"));
        assertEquals(60L, relatorioGravado("mrv-1").getToneladasEmitidas());
    }

    @Test
    void inv03_emitirMaisQueOVerificadoDeUmaVezFalhaSemAlterarEstado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);
        Map<String, String> antes = new HashMap<>(ledger);

        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, "cred-1", "mrv-1", 61L));

        assertEquals(antes, ledger);
        assertEquals(0L, relatorioGravado("mrv-1").getToneladasEmitidas());
    }

    @Test
    void inv03_falhaPorExcessoNaoAlteraEmitidasNemCriaCredito() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);
        contract.emitirCredito(ctx, "cred-1", "mrv-1", 50L);
        Map<String, String> antes = new HashMap<>(ledger);

        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, "cred-2", "mrv-1", 11L));

        assertEquals(antes, ledger);
        assertEquals(50L, relatorioGravado("mrv-1").getToneladasEmitidas());
        // O saldo restante continua emissivel.
        contract.emitirCredito(ctx, "cred-2", "mrv-1", 10L);
        assertEquals(60L, relatorioGravado("mrv-1").getToneladasEmitidas());
    }

    @Test
    void inv03_estouroDeLongViaAddExactFalhaSemAlterarEstado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorioComEmitidas("mrv-1", "proj-1", Long.MAX_VALUE, Long.MAX_VALUE - 5L);
        identidade(DEVELOPER_MSP);
        Map<String, String> antes = new HashMap<>(ledger);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.emitirCredito(ctx, "cred-1", "mrv-1", 10L));
        assertTrue(ex.getMessage().toLowerCase().contains("estouro"));
        assertEquals(antes, ledger);

        // Ate o limite exato ainda passa.
        contract.emitirCredito(ctx, "cred-1", "mrv-1", 5L);
        assertEquals(Long.MAX_VALUE, relatorioGravado("mrv-1").getToneladasEmitidas());
    }

    @Test
    void inv03_emissoesNaoSeContamEntreRelatoriosDiferentes() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        semearRelatorio("mrv-2", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);

        contract.emitirCredito(ctx, "cred-1", "mrv-1", 60L);
        contract.emitirCredito(ctx, "cred-2", "mrv-2", 60L);

        assertEquals(60L, relatorioGravado("mrv-1").getToneladasEmitidas());
        assertEquals(60L, relatorioGravado("mrv-2").getToneladasEmitidas());
        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, "cred-3", "mrv-1", 1L));
        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, "cred-4", "mrv-2", 1L));
    }

    @Test
    void inv03_verificacaoDeixaEmitidasZero() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.SUBMETIDO, 100L, 0L);
        identidade(VERIFIER_MSP);

        RelatorioMRV r = contract.verificarRelatorioMRV(ctx, "mrv-1", true, 60L, "ok");

        assertEquals(0L, r.getToneladasEmitidas());
        assertEquals(0L, relatorioGravado("mrv-1").getToneladasEmitidas());
    }

    @Test
    void inv03_colisaoDeIdDeCreditoFalhaSemAcumular() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);
        contract.emitirCredito(ctx, "cred-1", "mrv-1", 10L);
        Map<String, String> antes = new HashMap<>(ledger);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.emitirCredito(ctx, "cred-1", "mrv-1", 10L));
        assertTrue(ex.getMessage().contains("ja existe"));

        assertEquals(antes, ledger);
        assertEquals(10L, relatorioGravado("mrv-1").getToneladasEmitidas());
        assertEquals(10L, mapper.readValue(ledger.get("CREDITO_cred-1"), CreditoCarbono.class).getToneladasCO2e());
    }

    @Test
    void inv03_idDeCreditoVazioRejeitado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);
        Map<String, String> antes = new HashMap<>(ledger);

        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, " ", "mrv-1", 10L));
        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, null, "mrv-1", 10L));
        assertEquals(antes, ledger);
    }

    @Test
    void inv03_relatorioECreditoSaoGravadosNaMesmaTransacao() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);

        contract.emitirCredito(ctx, "cred-1", "mrv-1", 25L);

        org.mockito.InOrder ordem = org.mockito.Mockito.inOrder(stub);
        ordem.verify(stub).putStringState(org.mockito.ArgumentMatchers.eq("RELATORIO_MRV_mrv-1"), anyString());
        ordem.verify(stub).putStringState(org.mockito.ArgumentMatchers.eq("CREDITO_cred-1"), anyString());
        // Exatamente um putState de cada: sem estado intermediario nem escrita extra.
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.times(1))
                .putStringState(org.mockito.ArgumentMatchers.eq("RELATORIO_MRV_mrv-1"), anyString());
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.times(1))
                .putStringState(org.mockito.ArgumentMatchers.eq("CREDITO_cred-1"), anyString());
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.times(2))
                .putStringState(anyString(), anyString());
        assertEquals(25L, relatorioGravado("mrv-1").getToneladasEmitidas());
    }

    @Test
    void inv03_jsonCarregaToneladasEmitidasComoLong() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 60L);
        identidade(DEVELOPER_MSP);

        contract.emitirCredito(ctx, "cred-1", "mrv-1", 40L);

        com.fasterxml.jackson.databind.JsonNode no = mapper.readTree(ledger.get("RELATORIO_MRV_mrv-1"));
        assertTrue(no.get("toneladasEmitidas").isIntegralNumber());
        assertTrue(no.get("toneladasEmitidas").canConvertToLong());
        assertEquals("40", no.get("toneladasEmitidas").toString());
    }

    @Test
    void inv03_somaDeNParcelasInteirasIgualAoVerificadoExato() throws Exception {
        semearProjeto("proj-1", StatusProjeto.EM_MONITORAMENTO);
        semearRelatorio("mrv-1", "proj-1", StatusRelatorioMRV.VERIFICADO, 100L, 1000L);
        identidade(DEVELOPER_MSP);

        for (int i = 0; i < 100; i++) {
            contract.emitirCredito(ctx, "cred-" + i, "mrv-1", 10L);
        }

        assertEquals(1000L, relatorioGravado("mrv-1").getToneladasEmitidas());
        assertThrows(ChaincodeException.class, () -> contract.emitirCredito(ctx, "cred-x", "mrv-1", 1L));
    }

    // ------------------------------------------------------------------
    // INV-04: periodos de monitoramento sem sobreposicao (ADR-0005: P1 + Q2)
    // ------------------------------------------------------------------

    private static final String CONTROLE_PROJ_1 = "\u0000CONTROLE_PERIODOS_MRV\u0000proj-1\u0000";

    private RelatorioMRV submeterPeriodo(String idRelatorio, String idProjeto, String inicio, String fim) {
        identidade(DEVELOPER_MSP);
        return contract.submeterRelatorioMRV(ctx, idRelatorio, idProjeto, inicio, fim, 1000L, "sha256:" + idRelatorio);
    }

    private void exigirSobreposicaoRejeitada(String idRelatorio, String idProjeto, String inicio, String fim) {
        Map<String, String> antes = new HashMap<>(ledger);
        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> submeterPeriodo(idRelatorio, idProjeto, inicio, fim));
        assertTrue(ex.getMessage().contains("sobrepoe"), ex.getMessage());
        assertEquals(antes, ledger, "estado nao pode mudar em falha");
    }

    @Test
    void inv04_sobreposicaoParcialADireitaFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        exigirSobreposicaoRejeitada("mrv-2", "proj-1", "2024-01-15T00:00:00Z", "2024-03-01T00:00:00Z");
    }

    @Test
    void inv04_sobreposicaoParcialAEsquerdaFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-02-01T00:00:00Z", "2024-03-01T00:00:00Z");

        exigirSobreposicaoRejeitada("mrv-2", "proj-1", "2024-01-01T00:00:00Z", "2024-02-15T00:00:00Z");
    }

    @Test
    void inv04_periodoNovoContidoNoExistenteFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-12-31T00:00:00Z");

        exigirSobreposicaoRejeitada("mrv-2", "proj-1", "2024-03-01T00:00:00Z", "2024-04-01T00:00:00Z");
    }

    @Test
    void inv04_periodoNovoContemOExistenteFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-03-01T00:00:00Z", "2024-04-01T00:00:00Z");

        exigirSobreposicaoRejeitada("mrv-2", "proj-1", "2024-01-01T00:00:00Z", "2024-12-31T00:00:00Z");
    }

    @Test
    void inv04_mesmoIntervaloFalha() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        exigirSobreposicaoRejeitada("mrv-2", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");
    }

    @Test
    void inv04_bordaIgualFimDeUmInicioDoOutroFalha_P1() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        // novo comeca exatamente onde o existente termina
        exigirSobreposicaoRejeitada("mrv-2", "proj-1", "2024-02-01T00:00:00Z", "2024-03-01T00:00:00Z");
        // novo termina exatamente onde o existente comeca
        exigirSobreposicaoRejeitada("mrv-3", "proj-1", "2023-12-01T00:00:00Z", "2024-01-01T00:00:00Z");
    }

    @Test
    void inv04_adjacenteComUmDiaDeFolgaPassa() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        RelatorioMRV depois = submeterPeriodo("mrv-2", "proj-1", "2024-02-02T00:00:00Z", "2024-03-01T00:00:00Z");
        RelatorioMRV antes = submeterPeriodo("mrv-3", "proj-1", "2023-11-01T00:00:00Z", "2023-12-31T00:00:00Z");

        assertEquals(StatusRelatorioMRV.SUBMETIDO, depois.getStatus());
        assertEquals(StatusRelatorioMRV.SUBMETIDO, antes.getStatus());
        assertEquals(3, mapper.readTree(ledger.get(CONTROLE_PROJ_1)).get("totalRelatorios").asInt());
    }

    @Test
    void inv04_outroProjetoComMesmoPeriodoPassa() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        semearProjeto("proj-2", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        RelatorioMRV r = submeterPeriodo("mrv-2", "proj-2", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        assertEquals("proj-2", r.getIdProjeto());
        // prefixo de id: "proj-1" nao pode capturar "proj-10"
        semearProjeto("proj-10", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-3", "proj-10", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");
    }

    @Test
    void inv04_relatorioRejeitadoContinuaOcupandoOPeriodo_Q2() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");
        identidade(VERIFIER_MSP);
        RelatorioMRV rejeitado = contract.verificarRelatorioMRV(ctx, "mrv-1", false, 0L, "Evidencia insuficiente");
        assertEquals(StatusRelatorioMRV.REJEITADO, rejeitado.getStatus());

        exigirSobreposicaoRejeitada("mrv-2", "proj-1", "2024-01-10T00:00:00Z", "2024-01-20T00:00:00Z");
    }

    @Test
    void inv04_relatorioVerificadoTambemOcupaOPeriodo() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");
        identidade(VERIFIER_MSP);
        contract.verificarRelatorioMRV(ctx, "mrv-1", true, 600L, "ok");

        exigirSobreposicaoRejeitada("mrv-2", "proj-1", "2024-01-10T00:00:00Z", "2024-01-20T00:00:00Z");
    }

    @Test
    void inv04_fimAnteriorAoInicioRejeitadoEEstadoNaoMuda() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        Map<String, String> antes = new HashMap<>(ledger);

        assertThrows(ChaincodeException.class,
                () -> submeterPeriodo("mrv-1", "proj-1", "2024-02-01T00:00:00Z", "2024-01-01T00:00:00Z"));

        assertEquals(antes, ledger);
    }

    @Test
    void inv04_dataNulaVaziaOuMalFormadaRejeitadaEEstadoNaoMuda() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        Map<String, String> antes = new HashMap<>(ledger);

        assertThrows(ChaincodeException.class, () -> submeterPeriodo("mrv-1", "proj-1", null, "2024-02-01T00:00:00Z"));
        assertThrows(ChaincodeException.class, () -> submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", null));
        assertThrows(ChaincodeException.class, () -> submeterPeriodo("mrv-1", "proj-1", " ", "2024-02-01T00:00:00Z"));
        assertThrows(ChaincodeException.class, () -> submeterPeriodo("mrv-1", "proj-1", "01/01/2024", "2024-02-01T00:00:00Z"));
        assertThrows(ChaincodeException.class, () -> submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "ontem"));

        assertEquals(antes, ledger);
    }

    @Test
    void inv04_inicioIgualAoFimDoProprioRelatorioEhIntervaloValido() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);

        RelatorioMRV r = submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z");

        assertEquals(StatusRelatorioMRV.SUBMETIDO, r.getStatus());
    }

    @Test
    void inv04_submissaoLeEEscreveChaveDeControleDoProjetoNaMesmaTransacao() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        semearProjeto("proj-2", StatusProjeto.REGISTRADO);

        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        // Leitura e escrita da mesma chave de controle: duas submissoes concorrentes do
        // mesmo projeto colidem por versao (MVCC) na validacao do peer.
        org.mockito.Mockito.verify(stub).getStringState(CONTROLE_PROJ_1);
        org.mockito.Mockito.verify(stub).putStringState(org.mockito.ArgumentMatchers.eq(CONTROLE_PROJ_1), anyString());
        // Projeto diferente usa outra chave de controle.
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.never()).getStringState(
                "\u0000CONTROLE_PERIODOS_MRV\u0000proj-2\u0000");
        // Consulta limitada ao prefixo do projeto, nunca varredura global.
        org.mockito.Mockito.verify(stub).getStateByPartialCompositeKey("\u0000PERIODO_MRV\u0000proj-1\u0000");
        org.mockito.Mockito.verify(stub, org.mockito.Mockito.never()).getStateByRange(anyString(), anyString());
    }

    @Test
    void inv04_mesmaEntradaEmDoisLedgersProduzMesmoEstado() throws Exception {
        semearProjeto("proj-1", StatusProjeto.REGISTRADO);
        Map<String, String> inicial = new HashMap<>(ledger);

        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");
        Map<String, String> primeiro = new HashMap<>(ledger);

        ledger.clear();
        ledger.putAll(inicial);
        submeterPeriodo("mrv-1", "proj-1", "2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z");

        assertEquals(primeiro, ledger);
    }
}
