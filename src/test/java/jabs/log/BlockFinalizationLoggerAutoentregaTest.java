package jabs.log;

import jabs.network.networks.hotstuff.HotStuffTestConfig;
import jabs.scenario.ArqReddVotingScenario;
import jabs.scenario.HotStuffLANScenario;
import jabs.simulator.event.BlockFinalizationEvent;
import jabs.simulator.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D2 (ADR-0015), gap do critic na rodada 1: o {@link BlockFinalizationLogger} (arquivo {@code *-basic-log.csv}) passou
 * a gravar {@code TrafficUntilFinalization} SEM a autoentrega, mas sem expor os bytes em lugar nenhum. Estes testes
 * travam o formato: coluna PROPRIA {@code Cb_autoentrega_B} no FIM (as 9 existentes intactas), declaracao no comentario
 * do cabecalho, conservacao (valor antigo = novo + coluna) e ausencia da coluna nos protocolos que nao a separam.
 */
class BlockFinalizationLoggerAutoentregaTest {

    private static final String[] NOVE_COLUNAS = {"Time", "NodeID", "BlockHeight", "BlockHashCode", "BlockSize",
            "BlockCreationTime", "BlockCreator", "BLockFinalizationTime", "TrafficUntilFinalization"};

    /** Logger real, mas guarda cada evento de finalizacao junto com a linha que o logger de fato produziu. */
    private static final class Captura extends BlockFinalizationLogger {
        final List<BlockFinalizationEvent> eventos = new ArrayList<>();
        final List<String[]> linhas = new ArrayList<>();

        Captura(Path path) throws IOException {
            super(path);
        }

        @Override
        protected String[] csvEventOutput(Event event) {
            String[] linha = super.csvEventOutput(event);
            eventos.add((BlockFinalizationEvent) event);
            linhas.add(linha);
            return linha;
        }

        String[] cabecalho() {
            return csvHeaderOutput();
        }

        String comentario() {
            return csvStartingComment();
        }

        String[] linhaDe(BlockFinalizationEvent e) {
            return super.csvEventOutput(e);
        }
    }

    private static Captura rodaArq(Path csv) throws IOException {
        ArqReddVotingScenario sc = new ArqReddVotingScenario("autoentrega-basic", 1, 4, 20.0);
        Captura logger = new Captura(csv);
        sc.AddNewLogger(logger);
        sc.run();
        return logger;
    }

    private static List<String> linhasDeDados(Path csv) throws IOException {
        List<String> dados = new ArrayList<>();
        for (String l : Files.readAllLines(csv)) {
            if (!l.startsWith("#") && !l.isBlank()) {
                dados.add(l);
            }
        }
        return dados;
    }

    @Test
    void protocoloQueSeparaGanhaColunaPropriaNoFimComAsNoveIntactas(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("arq-basic-log.csv");
        Captura l = rodaArq(csv);

        String[] esperado = java.util.Arrays.copyOf(NOVE_COLUNAS, NOVE_COLUNAS.length + 1);
        esperado[NOVE_COLUNAS.length] = "Cb_autoentrega_B";
        assertArrayEquals(esperado, l.cabecalho(), "9 colunas existentes na mesma ordem + Cb_autoentrega_B NO FIM");

        List<String> dados = linhasDeDados(csv);
        assertFalse(dados.isEmpty());
        assertEquals(String.join(",", esperado), dados.get(0), "cabecalho gravado no arquivo");
        assertEquals(l.linhas.size() + 1, dados.size(), "uma linha por evento de finalizacao");
        for (int i = 1; i < dados.size(); i++) {
            assertEquals(esperado.length, dados.get(i).split(",", -1).length, "linha " + i + ": 10 colunas");
        }
    }

    @Test
    void cabecalhoDeclaraSemanticaGranularidadeEVersao(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("arq-basic-log.csv");
        Captura l = rodaArq(csv);
        String c = l.comentario();
        assertTrue(c.contains("Simulation name: autoentrega-basic"), "comentario de antes preservado: " + c);
        assertTrue(c.contains("TrafficUntilFinalization=C_B_total EXCLUDING self-delivery"), c);
        assertTrue(c.contains("Cb_autoentrega_B=bytes the node delivered to itself"), c);
        assertTrue(c.contains("own last column"), c);
        assertTrue(c.contains("one row per finalization event (NodeID, BlockHeight)"), "granularidade: " + c);
        assertTrue(c.contains("bytes received by THAT node until it finalized the block"), "granularidade: " + c);
        assertTrue(c.contains("not a network aggregate"), "granularidade: " + c);
        assertTrue(c.contains("-1=not measured (never zero)"), "sentinela: " + c);
        assertTrue(c.contains("TrafficUntilFinalization + Cb_autoentrega_B"), "conservacao declarada: " + c);
        assertTrue(c.contains("SimulatorVersion=" + LinkLoadRecorder.SIMULATOR_VERSION), "versao: " + c);
        List<String> brutas = Files.readAllLines(csv);
        assertTrue(brutas.get(0).startsWith("#") && brutas.get(0).contains("EXCLUDING self-delivery"),
                "a declaracao esta na linha de comentario do arquivo: " + brutas.get(0));
    }

    @Test
    void linhaTrazTrafegoSemAutoentregaEACelulaDeAutoentregaDoMesmoEvento(@TempDir Path dir) throws IOException {
        Captura l = rodaArq(dir.resolve("arq-basic-log.csv"));
        assertFalse(l.eventos.isEmpty(), "o ARQ deve finalizar blocos em 20 s");
        long somaAuto = 0;
        for (int i = 0; i < l.eventos.size(); i++) {
            BlockFinalizationEvent e = l.eventos.get(i);
            String[] row = l.linhas.get(i);
            assertEquals(10, row.length);
            assertEquals(Long.toString(e.getTrafficUntilFinalization()), row[8], "TrafficUntilFinalization = C_B sem autoentrega");
            assertEquals(Long.toString(e.getSelfDeliveryTraffic()), row[9], "Cb_autoentrega_B = autoentrega do mesmo evento");
            assertTrue(e.getSelfDeliveryTraffic() >= 0, "PBFT mede a autoentrega (nunca a sentinela)");
            somaAuto += e.getSelfDeliveryTraffic();
        }
        assertTrue(somaAuto > 0, "PBFT entrega pacotes a si mesmo: a coluna nao pode ser toda zero");
    }

    /** Valor antigo (com autoentrega) = TrafficUntilFinalization novo + Cb_autoentrega_B, com os numeros do caso TREE n=10. */
    @Test
    void conservacaoValorAntigoIgualNovoMaisColuna(@TempDir Path dir) throws IOException {
        Captura l = rodaArq(dir.resolve("arq-basic-log.csv"));
        BlockFinalizationEvent base = l.eventos.get(0);
        long[][] casos = {{3790, 758, 4548}, {3785, 757, 4542}, {15535, 3107, 18642}}; // {novo, autoentrega, antigo}
        for (long[] c : casos) {
            BlockFinalizationEvent e = new BlockFinalizationEvent(1.0, base.getNode(), base.getBlock(), c[0], -1L, c[1], -1L);
            String[] row = l.linhaDe(e);
            assertEquals(Long.toString(c[0]), row[8]);
            assertEquals(Long.toString(c[1]), row[9]);
            assertEquals(c[2], Long.parseLong(row[8]) + Long.parseLong(row[9]), "antigo = novo + autoentrega");
        }
        // sentinela: protocolo que nao mediu a autoentrega nunca vira zero
        BlockFinalizationEvent naoMedido = new BlockFinalizationEvent(1.0, base.getNode(), base.getBlock(), 100L);
        assertEquals("-1", l.linhaDe(naoMedido)[9]);
        BlockFinalizationEvent zeroMedido = new BlockFinalizationEvent(1.0, base.getNode(), base.getBlock(), 100L, -1L, 0L, -1L);
        assertEquals("0", l.linhaDe(zeroMedido)[9], "zero medido e distinto da sentinela");
    }

    @Test
    void protocoloQueNaoSeparaMantemOsNoveCamposEOComentarioDeAntes(@TempDir Path dir) throws IOException {
        HotStuffLANScenario sc = new HotStuffLANScenario("sem-coluna", 1, 4, 10.0, HotStuffTestConfig.config());
        Path csv = dir.resolve("hs-basic-log.csv");
        Captura l = new Captura(csv);
        sc.AddNewLogger(l);
        sc.run();
        assertArrayEquals(NOVE_COLUNAS, l.cabecalho(), "HotStuff ja filtrava a autoentrega: saida de antes");
        String c = l.comentario();
        assertFalse(c.contains("Cb_autoentrega_B"), c);
        assertFalse(c.contains("SimulatorVersion"), c);
        assertFalse(l.eventos.isEmpty());
        for (String[] row : l.linhas) {
            assertEquals(9, row.length);
        }
    }
}
