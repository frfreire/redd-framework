package br.com.redd.chaincode;

import br.com.redd.chaincode.model.CreditoCarbono;
import br.com.redd.chaincode.model.RelatorioMRV;
import br.com.redd.chaincode.model.StatusCredito;
import br.com.redd.chaincode.model.StatusRelatorioMRV;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hyperledger.fabric.contract.execution.JSONTransactionSerializer;
import org.hyperledger.fabric.contract.metadata.TypeSchema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INV-11 / ADR-0002: toneladas inteiras (long, kgCO2e), nunca ponto flutuante. */
class ToneladasTest {

    private static final long UM_MILHAO_DE_TONELADAS_EM_KG = 1_000_000_000L;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ---- soma exata -------------------------------------------------

    @Test
    void somaExata_naoPerdePrecisaoOndeDoublePerde() {
        // 0.1 + 0.2 != 0.3 em double; em kg inteiros a soma e exata.
        assertEquals(300L, Toneladas.somar(100L, 200L));
        // Acima de 2^53 o double ja nao representa o inteiro seguinte; long sim.
        long grande = (1L << 53) + 1;
        assertEquals(grande + 1, Toneladas.somar(grande, 1L));
        assertEquals(UM_MILHAO_DE_TONELADAS_EM_KG * 3,
                Toneladas.somar(UM_MILHAO_DE_TONELADAS_EM_KG * 2, UM_MILHAO_DE_TONELADAS_EM_KG));
    }

    @Test
    void somaDeParcelasIgualAoTotalSemResiduo() {
        long total = 0;
        for (int i = 0; i < 1000; i++) {
            total = Toneladas.somar(total, 1L);
        }
        assertEquals(1000L, total);
    }

    // ---- estouro ----------------------------------------------------

    @Test
    void estouroNaSomaELancadoNaoTruncado() {
        RedContractException ex = assertThrows(RedContractException.class,
                () -> Toneladas.somar(Long.MAX_VALUE, 1L));
        assertTrue(ex.getMessage().contains("Estouro"));
        assertThrows(ArithmeticException.class, () -> Math.addExact(Long.MAX_VALUE, 1L));
    }

    @Test
    void estouroNaMultiplicacaoELancado() {
        assertThrows(RedContractException.class, () -> Toneladas.multiplicar(Long.MAX_VALUE, 2L));
        assertEquals(6_000L, Toneladas.multiplicar(6L, 1000L));
    }

    // ---- validacao de positivo --------------------------------------

    @Test
    void rejeitaZeroENegativo() {
        assertThrows(RedContractException.class, () -> Toneladas.exigirPositivo(0L, "x"));
        RedContractException ex = assertThrows(RedContractException.class,
                () -> Toneladas.exigirPositivo(-5L, "toneladasCO2e"));
        assertTrue(ex.getMessage().contains("toneladasCO2e"));
        assertEquals(1L, Toneladas.exigirPositivo(1L, "x"));
    }

    // ---- rejeicao na borda do shim (argumento long de transacao) -----

    @Test
    void shimNaoConvertePonto5ParaLong() throws Exception {
        JSONTransactionSerializer ser = new JSONTransactionSerializer();
        TypeSchema schema = TypeSchema.typeConvert(long.class);
        assertEquals(1500L, ser.fromBuffer("1500".getBytes(StandardCharsets.UTF_8), schema));
        for (String ruim : new String[] {"1.5", "1500.0", "1e3", "abc"}) {
            boolean rejeitou;
            Object valor = null;
            try {
                valor = ser.fromBuffer(ruim.getBytes(StandardCharsets.UTF_8), schema);
                rejeitou = false;
            } catch (RuntimeException e) {
                rejeitou = true;
            }
            assertTrue(rejeitou, "shim aceitou '" + ruim + "' como " + valor);
        }
    }

    // ---- serializacao JSON: long, sem ponto decimal -----------------

    @Test
    void relatorioSerializaToneladasComoInteiroJson() throws Exception {
        RelatorioMRV r = new RelatorioMRV("mrv-1", "proj-1", Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-02-01T00:00:00Z"), 1_234_567L, "sha256:abc", StatusRelatorioMRV.SUBMETIDO);
        String json = mapper.writeValueAsString(r);
        JsonNode no = mapper.readTree(json);

        assertTrue(no.get("toneladasCO2eEstimadas").isIntegralNumber());
        assertTrue(json.contains("\"toneladasCO2eEstimadas\":1234567"), json);
        assertFalse(json.contains("1234567.0"), json);
        assertEquals(r.getToneladasCO2eEstimadas(),
                mapper.readValue(json, RelatorioMRV.class).getToneladasCO2eEstimadas());
    }

    @Test
    void creditoSerializaToneladasComoInteiroJson() throws Exception {
        long valorAcimaDe2Elevado53 = (1L << 53) + 1;
        CreditoCarbono c = new CreditoCarbono("c-1", "proj-1", "mrv-1", valorAcimaDe2Elevado53,
                "DeveloperMSP", StatusCredito.EMITIDO);
        String json = mapper.writeValueAsString(c);
        JsonNode no = mapper.readTree(json);

        assertTrue(no.get("toneladasCO2e").isIntegralNumber());
        assertTrue(json.contains("\"toneladasCO2e\":" + valorAcimaDe2Elevado53), json);
        assertEquals(valorAcimaDe2Elevado53, mapper.readValue(json, CreditoCarbono.class).getToneladasCO2e());
    }

    @Test
    void estadoComFracaoEmToneladasEhRejeitadoPeloMapperDoContrato() throws Exception {
        java.lang.reflect.Field f = RedContract.class.getDeclaredField("mapper");
        f.setAccessible(true);
        ObjectMapper doContrato = (ObjectMapper) f.get(new RedContract());
        assertEquals(7L, doContrato.readValue("{\"toneladasCO2e\":7}", CreditoCarbono.class).getToneladasCO2e());
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> doContrato.readValue("{\"toneladasCO2e\":1.5}", CreditoCarbono.class));
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> doContrato.readValue("{\"toneladasCO2eEstimadas\":1000.0}", RelatorioMRV.class));
    }

    // ---- nenhum campo/metodo de tonelada em ponto flutuante -----------

    @Test
    void modelosNaoTemCampoDeToneladaEmPontoFlutuante() {
        for (Class<?> modelo : new Class<?>[] {RelatorioMRV.class, CreditoCarbono.class}) {
            for (Field f : modelo.getDeclaredFields()) {
                if (f.getName().toLowerCase().contains("tonelada")) {
                    assertEquals(long.class, f.getType(), modelo.getSimpleName() + "." + f.getName());
                }
            }
            for (Method m : modelo.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("tonelada")) {
                    Class<?> t = m.getName().startsWith("get") ? m.getReturnType() : m.getParameterTypes()[0];
                    assertEquals(long.class, t, modelo.getSimpleName() + "." + m.getName());
                }
            }
        }
    }

    @Test
    void argumentosDeToneladasDoContratoSaoLong() throws Exception {
        for (Method m : RedContract.class.getDeclaredMethods()) {
            for (java.lang.reflect.Parameter p : m.getParameters()) {
                if (p.getName().toLowerCase().contains("tonelada")) {
                    assertEquals(long.class, p.getType(), m.getName() + "(" + p.getName() + ")");
                }
            }
        }
        // parameters sem -parameters aparecem como arg0; garante que ha ao menos
        // os dois metodos de tonelada com long na assinatura.
        assertEquals(long.class, RedContract.class.getMethod("emitirCredito",
                org.hyperledger.fabric.contract.Context.class, String.class, String.class, long.class)
                .getParameterTypes()[3]);
        assertEquals(long.class, RedContract.class.getMethod("submeterRelatorioMRV",
                org.hyperledger.fabric.contract.Context.class, String.class, String.class, String.class,
                String.class, long.class, String.class).getParameterTypes()[5]);
    }
}
