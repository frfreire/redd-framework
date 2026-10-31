package br.com.redd.chaincode;

import br.com.redd.chaincode.model.CreditoCarbono;
import br.com.redd.chaincode.model.StatusCredito;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.protos.peer.QueryResponseMetadata;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.KeyValue;
import org.hyperledger.fabric.shim.ledger.QueryResultsIteratorWithMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1-T6: toda rich query do contrato e paginada. O stub simula o CouchDB:
 * filtra por idProjeto, ordena por _id e devolve bookmark opaco ("pos:N").
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedContractPaginacaoTest {

    @Mock
    private Context ctx;
    @Mock
    private ChaincodeStub stub;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Map<String, String> ledger = new TreeMap<>();
    private final List<String> seletores = new ArrayList<>();
    private RedContract contract;

    @BeforeEach
    void setUp() throws Exception {
        contract = new RedContract();
        when(ctx.getStub()).thenReturn(stub);
        lenient().when(stub.getQueryResultWithPagination(anyString(), anyInt(), anyString()))
                .thenAnswer(inv -> couchdb(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
    }

    private QueryResultsIteratorWithMetadata<KeyValue> couchdb(String seletor, int pageSize, String bookmark)
            throws Exception {
        seletores.add(seletor);
        JsonNode q = mapper.readTree(seletor);
        String idProjeto = q.path("selector").path("idProjeto").asText();
        List<Map.Entry<String, String>> todos = new ArrayList<>();
        for (Map.Entry<String, String> e : ledger.entrySet()) {
            if (e.getKey().startsWith("CREDITO_")
                    && idProjeto.equals(mapper.readTree(e.getValue()).path("idProjeto").asText())) {
                todos.add(e);
            }
        }
        int inicio = bookmark.isEmpty() ? 0 : Integer.parseInt(bookmark.substring("pos:".length()));
        int fim = Math.min(todos.size(), inicio + pageSize);
        List<KeyValue> pagina = new ArrayList<>();
        for (Map.Entry<String, String> e : todos.subList(Math.min(inicio, fim), fim)) {
            KeyValue kv = mock(KeyValue.class);
            when(kv.getStringValue()).thenReturn(e.getValue());
            pagina.add(kv);
        }
        @SuppressWarnings("unchecked")
        QueryResultsIteratorWithMetadata<KeyValue> it = mock(QueryResultsIteratorWithMetadata.class);
        when(it.iterator()).thenAnswer(x -> pagina.iterator());
        when(it.getMetadata()).thenReturn(QueryResponseMetadata.newBuilder()
                .setBookmark("pos:" + fim).setFetchedRecordsCount(pagina.size()).build());
        return it;
    }

    private void semear(String projeto, int quantidade) throws Exception {
        for (int i = 1; i <= quantidade; i++) {
            String id = projeto + "-c" + String.format("%03d", i);
            CreditoCarbono c = new CreditoCarbono(id, projeto, "mrv-1", 10L, "MarketplaceMSP", StatusCredito.EMITIDO);
            ledger.put("CREDITO_" + id, mapper.writeValueAsString(c));
        }
    }

    private JsonNode listar(String projeto, int pageSize, String bookmark) throws Exception {
        return mapper.readTree(contract.listarCreditosPorProjeto(ctx, projeto, pageSize, bookmark));
    }

    @Test
    void paginaCheia_devolvePageSizeItensEBookmarkParaAProxima() throws Exception {
        semear("proj-1", 5);
        semear("proj-2", 3);

        JsonNode p = listar("proj-1", 2, "");

        assertEquals(2, p.path("quantidade").asInt());
        assertEquals(2, p.path("pageSize").asInt());
        assertEquals(2, p.path("creditos").size());
        assertEquals("proj-1-c001", p.path("creditos").get(0).path("id").asText());
        assertEquals("proj-1-c002", p.path("creditos").get(1).path("id").asText());
        assertEquals("pos:2", p.path("bookmark").asText());
    }

    @Test
    void ultimaPagina_vemIncompletaEPercorrerTudoNaoRepeteNemPerde() throws Exception {
        semear("proj-1", 5);
        semear("proj-2", 3);

        List<String> ids = new ArrayList<>();
        String bookmark = "";
        int paginas = 0;
        JsonNode p;
        do {
            p = listar("proj-1", 2, bookmark);
            p.path("creditos").forEach(c -> ids.add(c.path("id").asText()));
            bookmark = p.path("bookmark").asText();
            paginas++;
        } while (p.path("quantidade").asInt() == p.path("pageSize").asInt());

        assertEquals(3, paginas);
        assertEquals(1, p.path("quantidade").asInt());
        assertEquals(List.of("proj-1-c001", "proj-1-c002", "proj-1-c003", "proj-1-c004", "proj-1-c005"), ids);
    }

    @Test
    void paginaCheiaNoFim_seguinteVemVazia() throws Exception {
        semear("proj-1", 4);

        JsonNode ultima = listar("proj-1", 2, listar("proj-1", 2, "").path("bookmark").asText());
        JsonNode alemDoFim = listar("proj-1", 2, ultima.path("bookmark").asText());

        assertEquals(2, ultima.path("quantidade").asInt());
        assertEquals(0, alemDoFim.path("quantidade").asInt());
        assertEquals(0, alemDoFim.path("creditos").size());
    }

    @Test
    void pageSizeAcimaDoMaximo_falhaSemConsultarOLedger() throws Exception {
        semear("proj-1", 3);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.listarCreditosPorProjeto(ctx, "proj-1", RedContract.PAGE_SIZE_MAXIMO + 1, ""));

        assertTrue(ex.getMessage().contains("entre 1 e " + RedContract.PAGE_SIZE_MAXIMO));
        verify(stub, never()).getQueryResultWithPagination(anyString(), anyInt(), anyString());
    }

    @Test
    void pageSizeNoMaximo_eAceito() throws Exception {
        semear("proj-1", 3);

        JsonNode p = listar("proj-1", RedContract.PAGE_SIZE_MAXIMO, "");

        assertEquals(3, p.path("quantidade").asInt());
        verify(stub).getQueryResultWithPagination(anyString(), org.mockito.ArgumentMatchers.eq(RedContract.PAGE_SIZE_MAXIMO), anyString());
    }

    @Test
    void pageSizeZeroOuNegativo_falha() {
        assertThrows(ChaincodeException.class, () -> contract.listarCreditosPorProjeto(ctx, "proj-1", 0, ""));
        assertThrows(ChaincodeException.class, () -> contract.listarCreditosPorProjeto(ctx, "proj-1", -5, ""));
    }

    @Test
    void bookmarkVazio_eNuloSaoPrimeiraPaginaEChegamAoStubComoVazio() throws Exception {
        semear("proj-1", 3);

        JsonNode comVazio = listar("proj-1", 2, "");
        JsonNode comNulo = listar("proj-1", 2, null);

        assertEquals(comVazio, comNulo);
        assertEquals("proj-1-c001", comNulo.path("creditos").get(0).path("id").asText());
        verify(stub, org.mockito.Mockito.times(2)).getQueryResultWithPagination(anyString(), anyInt(), org.mockito.ArgumentMatchers.eq(""));
    }

    @Test
    void projetoSemCreditos_paginaVaziaComBookmarkTextoVazioOuOpaco() throws Exception {
        JsonNode p = listar("proj-9", 10, "");

        assertEquals(0, p.path("quantidade").asInt());
        assertEquals(0, p.path("creditos").size());
        assertTrue(p.path("bookmark").isTextual());
    }

    @Test
    void naoUsaGetQueryResultSemPaginacao() throws Exception {
        semear("proj-1", 1);

        listar("proj-1", 10, "");

        verify(stub, never()).getQueryResult(anyString());
    }

    @Test
    void formatoDeterministico_camposEmOrdemFixaEMesmaSaidaEmDuasExecucoes() throws Exception {
        semear("proj-1", 3);

        String a = contract.listarCreditosPorProjeto(ctx, "proj-1", 2, "");
        String b = new RedContract().listarCreditosPorProjeto(ctx, "proj-1", 2, "");

        assertEquals(a, b);
        assertTrue(a.startsWith("{\"bookmark\":\"pos:2\",\"creditos\":[{"), a);
        assertTrue(a.endsWith("],\"pageSize\":2,\"quantidade\":2}"), a);
    }

    @Test
    void seletor_ordenaPorIndiceEUsaIndiceExistente() throws Exception {
        semear("proj-1", 1);
        listar("proj-1", 10, "");

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(stub).getQueryResultWithPagination(cap.capture(), anyInt(), anyString());
        JsonNode q = mapper.readTree(cap.getValue());

        // Campos do indice META-INF/.../indexIdProjeto.json: ["idProjeto","_id"]
        assertEquals("idProjeto", q.path("sort").get(0).fieldNames().next());
        assertEquals("_id", q.path("sort").get(1).fieldNames().next());
        assertEquals("indexIdProjetoDoc", q.path("use_index").get(0).asText());
        assertEquals("indexIdProjeto", q.path("use_index").get(1).asText());
        assertEquals("proj-1", q.path("selector").path("idProjeto").asText());
        assertEquals("^CREDITO_", q.path("selector").path("_id").path("$regex").asText());
    }

    @Test
    void idProjetoComAspasNaoInjetaCamposNoSeletor() throws Exception {
        listar("p\",\"x\":\"y", 10, "");

        JsonNode q = mapper.readTree(seletores.get(0));
        assertEquals("p\",\"x\":\"y", q.path("selector").path("idProjeto").asText());
        assertTrue(q.path("selector").path("x").isMissingNode());
    }

    @Test
    void idProjetoVazio_falha() {
        assertThrows(ChaincodeException.class, () -> contract.listarCreditosPorProjeto(ctx, " ", 10, ""));
    }
}
