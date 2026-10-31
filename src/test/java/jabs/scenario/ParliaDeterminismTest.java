package jabs.scenario;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0006, tarefa 3: o atraso de voto do Parlia/AMBIFY vem do RandomnessEngine do cenario
 * (semente), nao de Math.random(). Mesma semente => mesma saida; sementes distintas => saidas
 * distintas; atraso uniforme em [0,1; 0,6) s. Usa n = 10: Parlia com n &lt; 10 nao termina o setup.
 * As comparacoes entre execucoes rodam em processos JVM separados (ver {@link ParliaRunMain}).
 */
class ParliaDeterminismTest {

    private static final double STOP = 60;

    /** Executa ParliaRunMain num processo novo e devolve a saida padrao. */
    private static String emProcessoNovo(long seed) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path saida = Files.createTempFile("parlia-run", ".out");
        try {
            Process p = new ProcessBuilder(java, "-cp", cp, "jabs.scenario.ParliaRunMain",
                    Long.toString(seed), Double.toString(STOP))
                    .redirectOutput(saida.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            assertTrue(p.waitFor(120, TimeUnit.SECONDS), "execucao nao terminou (n < 10 trava o setup)");
            assertEquals(0, p.exitValue(), "processo filho falhou");
            return Files.readString(saida);
        } finally {
            Files.deleteIfExists(saida);
        }
    }

    @Test
    void mesmaSementeMesmaSaidaEmDoisProcessos() throws Exception {
        String a = emProcessoNovo(1);
        String b = emProcessoNovo(1);
        assertTrue(a.contains("FIN|"), "Parlia deve finalizar blocos em " + STOP + " s");
        assertTrue(a.contains("VOTE|"), "o laco deve agendar votos");
        assertEquals(a, b, "mesma semente => mesmos blocos, votos (atrasos) e finalizacoes");
    }

    @Test
    void sementesDistintasSaidasDistintas() throws Exception {
        String a = emProcessoNovo(1);
        String b = emProcessoNovo(2);
        assertNotEquals(a, b, "sementes distintas => saidas distintas");
    }

    @Test
    void atrasoDeVotoFicaEmZeroUmAZeroSeis() throws Exception {
        ParliaRunMain.Resultado r = ParliaRunMain.simular(3, STOP);
        assertTrue(r.votosAgendados() > 0, "o laco deve agendar votos");
        assertTrue(r.minDelay() >= 0.1 - 1e-9, "atraso minimo >= 0,1 s: " + r.minDelay());
        assertTrue(r.maxDelay() < 0.6 + 1e-9, "atraso maximo < 0,6 s: " + r.maxDelay());
        assertTrue(r.maxDelay() - r.minDelay() > 0.3, "o atraso e variavel e cobre o intervalo");
    }

    @Test
    void parliaNaoUsaMathRandomNemRandomNovo() throws Exception {
        String src = Files.readString(Paths.get("src/main/java/jabs/consensus/algorithm/Parlia.java"));
        String codigo = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertFalse(codigo.contains("Math.random"), "Parlia nao pode usar Math.random()");
        assertFalse(codigo.contains("new Random(") || codigo.contains("new java.util.Random("),
                "Parlia nao pode criar java.util.Random");
        assertTrue(codigo.contains("getRandom()"), "o gerador vem de network.getRandom()");
        assertTrue(new File("src/main/java/jabs/consensus/algorithm/Parlia.java").exists());
    }
}
