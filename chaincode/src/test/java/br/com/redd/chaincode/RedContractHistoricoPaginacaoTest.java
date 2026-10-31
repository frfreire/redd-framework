package br.com.redd.chaincode;

import br.com.redd.chaincode.model.CreditoCarbono;
import br.com.redd.chaincode.model.StatusCredito;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hyperledger.fabric.contract.Context;
import org.hyperledger.fabric.shim.ChaincodeException;
import org.hyperledger.fabric.shim.ChaincodeStub;
import org.hyperledger.fabric.shim.ledger.KeyModification;
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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1-T6: {@code historicoCredito} e paginado por offset/pageSize. O stub
 * simula {@code getHistoryForKey} e conta quantas versoes o iterador entregou,
 * para provar que a leitura para ao encher a pagina.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedContractHistoricoPaginacaoTest {

    @Mock
    private Context ctx;
    @Mock
    private ChaincodeStub stub;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private RedContract contract;
    private final AtomicInteger entregues = new AtomicInteger();
    private QueryResultsIterator<KeyModification> iteradorAberto;

    @BeforeEach
    void setUp() {
        contract = new RedContract();
        when(ctx.getStub()).thenReturn(stub);
    }

    private KeyModification versao(int i, boolean apagada) throws Exception {
        KeyModification mod = mock(KeyModification.class);
        CreditoCarbono c = new CreditoCarbono("cred-1", "proj-1", "mrv-1", 10L, "owner-" + i, StatusCredito.EMITIDO);
        when(mod.isDeleted()).thenReturn(apagada);
        when(mod.getStringValue()).thenReturn(apagada ? "" : mapper.writeValueAsString(c));
        when(mod.getTimestamp()).thenReturn(Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i));
        return mod;
    }

    /** Historico com {@code total} versoes; as posicoes em {@code apagadas} sao delecoes. */
    @SuppressWarnings("unchecked")
    private void historico(int total, int... apagadas) throws Exception {
        List<KeyModification> mods = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            boolean apagada = false;
            for (int a : apagadas) {
                apagada |= a == i;
            }
            mods.add(versao(i, apagada));
        }
        iteradorAberto = mock(QueryResultsIterator.class);
        when(iteradorAberto.iterator()).thenAnswer(x -> {
            Iterator<KeyModification> base = mods.iterator();
            return new Iterator<KeyModification>() {
                public boolean hasNext() {
                    return base.hasNext();
                }

                public KeyModification next() {
                    entregues.incrementAndGet();
                    return base.next();
                }
            };
        });
        when(stub.getHistoryForKey("CREDITO_cred-1")).thenReturn(iteradorAberto);
    }

    private JsonNode consultar(int offset, int pageSize) throws Exception {
        return mapper.readTree(contract.historicoCredito(ctx, "cred-1", offset, pageSize));
    }

    private static List<String> donos(JsonNode p) {
        List<String> r = new ArrayList<>();
        p.path("eventos").forEach(e -> r.add(e.path("paraMspId").asText()));
        return r;
    }

    @Test
    void paginaCheia_devolvePageSizeEventosETemMais() throws Exception {
        historico(5);

        JsonNode p = consultar(0, 2);

        assertEquals(List.of("owner-0", "owner-1"), donos(p));
        assertEquals(2, p.path("quantidade").asInt());
        assertEquals(2, p.path("pageSize").asInt());
        assertEquals(0, p.path("offset").asInt());
        assertTrue(p.path("temMais").asBoolean());
    }

    @Test
    void paginaCheia_paraDepoisDeEncherAPagina_leUmaVersaoAMaisSomente() throws Exception {
        historico(1000);

        JsonNode p = consultar(0, 3);

        assertEquals(3, p.path("quantidade").asInt());
        assertTrue(p.path("temMais").asBoolean());
        assertEquals(4, entregues.get(), "pagina de 3 + 1 sentinela; nao varre as 1000 versoes");
        verify(iteradorAberto).close();
    }

    @Test
    void ultimaPagina_incompletaESemMais_epercorrerTudoNaoRepeteNemPerde() throws Exception {
        historico(5);

        List<String> todos = new ArrayList<>();
        int offset = 0;
        JsonNode p;
        int paginas = 0;
        do {
            p = consultar(offset, 2);
            todos.addAll(donos(p));
            offset += p.path("quantidade").asInt();
            paginas++;
        } while (p.path("temMais").asBoolean());

        assertEquals(3, paginas);
        assertEquals(1, p.path("quantidade").asInt());
        assertFalse(p.path("temMais").asBoolean());
        assertEquals(List.of("owner-0", "owner-1", "owner-2", "owner-3", "owner-4"), todos);
    }

    @Test
    void paginaCheiaExatamenteNoFim_temMaisFalso() throws Exception {
        historico(4);

        JsonNode p = consultar(2, 2);

        assertEquals(List.of("owner-2", "owner-3"), donos(p));
        assertFalse(p.path("temMais").asBoolean());
    }

    @Test
    void pageSizeAcimaDoMaximo_falhaSemConsultarOLedger() throws Exception {
        historico(3);

        ChaincodeException ex = assertThrows(ChaincodeException.class,
                () -> contract.historicoCredito(ctx, "cred-1", 0, RedContract.PAGE_SIZE_MAXIMO + 1));

        assertTrue(ex.getMessage().contains("entre 1 e " + RedContract.PAGE_SIZE_MAXIMO));
        verify(stub, never()).getHistoryForKey(anyString());
    }

    @Test
    void pageSizeNoMaximo_eAceito() throws Exception {
        historico(RedContract.PAGE_SIZE_MAXIMO + 5);

        JsonNode p = consultar(0, RedContract.PAGE_SIZE_MAXIMO);

        assertEquals(RedContract.PAGE_SIZE_MAXIMO, p.path("quantidade").asInt());
        assertTrue(p.path("temMais").asBoolean());
    }

    @Test
    void pageSizeZeroOuNegativoEOffsetNegativo_falham() {
        assertThrows(ChaincodeException.class, () -> contract.historicoCredito(ctx, "cred-1", 0, 0));
        assertThrows(ChaincodeException.class, () -> contract.historicoCredito(ctx, "cred-1", 0, -1));
        assertThrows(ChaincodeException.class, () -> contract.historicoCredito(ctx, "cred-1", -1, 10));
        verify(stub, never()).getHistoryForKey(anyString());
    }

    @Test
    void offsetAlemDoFim_paginaVaziaSemMais() throws Exception {
        historico(3);

        JsonNode p = consultar(50, 10);

        assertEquals(0, p.path("quantidade").asInt());
        assertEquals(0, p.path("eventos").size());
        assertFalse(p.path("temMais").asBoolean());
        assertEquals(50, p.path("offset").asInt());
        verify(iteradorAberto).close();
    }

    @Test
    void delecoesNaoContamOffsetNemPagina() throws Exception {
        historico(5, 1);

        JsonNode p = consultar(1, 2);

        assertEquals(List.of("owner-2", "owner-3"), donos(p));
        assertTrue(p.path("temMais").asBoolean());
    }

    @Test
    void creditoSemHistorico_paginaVaziaEIteradorFechado() throws Exception {
        historico(0);

        JsonNode p = consultar(0, 10);

        assertEquals(0, p.path("quantidade").asInt());
        assertFalse(p.path("temMais").asBoolean());
        verify(iteradorAberto).close();
    }

    @Test
    void formatoDeterministico_camposEmOrdemFixaEMesmaSaidaEmDuasExecucoes() throws Exception {
        historico(3);

        String a = contract.historicoCredito(ctx, "cred-1", 0, 2);
        String b = new RedContract().historicoCredito(ctx, "cred-1", 0, 2);

        assertEquals(a, b);
        assertTrue(a.startsWith("{\"eventos\":[{"), a);
        assertTrue(a.endsWith("],\"offset\":0,\"pageSize\":2,\"quantidade\":2,\"temMais\":true}"), a);
    }
}
