package jabs.example;

import jabs.config.ByzantineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BYZ-fora-da-faixa-hotstuff (ADR-0015, decisao 3 de 10/11/2026 e D3(a)): {@code f > floor((n-1)/3)} no HOTSTUFF falha
 * alto sem {@code --allow-beyond-bft-threshold=true}; com a flag roda e e rotulado com linha '#'. Valores
 * {@code --hs-*} abaixo sao FIXTURE DE TESTE, nao calibracao.
 */
class MVPComparisonBeyondThresholdTest {
    private static final ByzantineConfig.AttackType EQ = ByzantineConfig.AttackType.EQUIVOCATION;
    private static final List<String> HS = List.of("HOTSTUFF");

    private static Map<String, String> cli(String count, String allow) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("byzantine-count", count);
        if (allow != null) {
            m.put("allow-beyond-bft-threshold", allow);
        }
        return m;
    }

    private static int resolve(List<Integer> ns, String count, String allow) {
        return MVPComparison.resolveByzantineCount(HS, EQ, cli(count, allow), ns);
    }

    @Test
    void limiteEInteiroFloorNMenos1Sobre3() {
        int[][] esperado = {{1, 0}, {3, 0}, {4, 1}, {6, 1}, {7, 2}, {10, 3}, {13, 4}, {16, 5}, {64, 21}, {128, 42}};
        for (int[] e : esperado) {
            assertEquals(e[1], MVPComparison.bftLimit(e[0]), "n=" + e[0]);
        }
    }

    @Test
    void dentroDaFaixaEnoLimiteNaoPrecisamDeFlag() {
        for (int[] nf : new int[][] {{4, 1}, {7, 1}, {7, 2}, {10, 3}, {13, 4}, {16, 5}}) {
            assertEquals(nf[1], resolve(List.of(nf[0]), String.valueOf(nf[1]), null), "n=" + nf[0] + " f=" + nf[1]);
            // com a flag e dentro da faixa: o mesmo retorno e NENHUM rotulo
            assertEquals(nf[1], resolve(List.of(nf[0]), String.valueOf(nf[1]), "true"));
            assertEquals(List.of(), MVPComparison.beyondThresholdLines(EQ, nf[1], List.of(nf[0])));
        }
        assertEquals(List.of(), MVPComparison.beyondThresholdLines(null, 5, List.of(4)));
        assertEquals(List.of(), MVPComparison.beyondThresholdLines(ByzantineConfig.AttackType.NONE, 5, List.of(4)));
    }

    @Test
    void floorMaisUmSemFlagFalhaAltoCitandoNfELimite() {
        for (int[] nf : new int[][] {{4, 2}, {7, 3}, {10, 4}, {13, 5}}) {
            String f = String.valueOf(nf[1]);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> resolve(List.of(nf[0]), f, null), "n=" + nf[0]);
            assertTrue(e.getMessage().contains("n=" + nf[0]), e.getMessage());
            assertTrue(e.getMessage().contains("f=" + nf[1]), e.getMessage());
            assertTrue(e.getMessage().contains("floor((n-1)/3)=" + MVPComparison.bftLimit(nf[0])), e.getMessage());
            assertTrue(e.getMessage().contains("--allow-beyond-bft-threshold=true"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> resolve(List.of(nf[0]), f, "false"));
        }
        // basta UM n fora da faixa entre os de --validators; a mensagem cita so o(s) n fora
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> resolve(List.of(7, 4, 10), "2", null));
        assertTrue(e.getMessage().contains("n=4 f=2 limite floor((n-1)/3)=1"), e.getMessage());
        assertTrue(!e.getMessage().contains("n=7 f="), e.getMessage());
        assertTrue(!e.getMessage().contains("n=10 f="), e.getMessage());
    }

    @Test
    void floorMaisUmComFlagRodaERotulaPorN() {
        assertEquals(2, resolve(List.of(4), "2", "true"));
        assertEquals(2, resolve(List.of(4, 7, 10), "2", "true"));
        List<String> linhas = MVPComparison.beyondThresholdLines(EQ, 2, List.of(4, 7, 10));
        assertEquals(1, linhas.size(), "so n=4 esta fora da faixa (7 e 10 tem limite >= 2): " + linhas);
        assertTrue(linhas.get(0).startsWith("# fora_da_faixa=true n=4 byzantine_count=2 limite_bft=1"), linhas.get(0));
        List<String> duas = MVPComparison.beyondThresholdLines(EQ, 3, List.of(4, 7, 10));
        assertEquals(2, duas.size(), duas.toString());
        assertTrue(duas.get(0).contains("n=4 ") && duas.get(0).contains("limite_bft=1"), duas.get(0));
        assertTrue(duas.get(1).contains("n=7 ") && duas.get(1).contains("limite_bft=2"), duas.get(1));
        // f > n continua sendo erro, com ou sem a flag
        assertThrows(IllegalArgumentException.class, () -> resolve(List.of(4), "5", "true"));
    }

    @Test
    void valorInvalidoDaFlagFalhaAltoComHotStuff() {
        for (String v : new String[] {"TRUE", "1", "yes", "", "sim"}) {
            assertThrows(IllegalArgumentException.class, () -> resolve(List.of(4), "1", v), "[" + v + "]");
        }
        assertEquals(1, resolve(List.of(4), "1", "false"));
    }

    @Test
    void flagSemAtaqueOuSemHotStuffSegueOPrecedenteDaContagem() {
        // HOTSTUFF sem ataque: =true seria ignorada em silencio -> falha alto (como --byzantine-count sem ataque)
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.resolveByzantineCount(HS, null,
                Map.of("allow-beyond-bft-threshold", "true"), List.of(4)));
        assertThrows(IllegalArgumentException.class, () -> MVPComparison.resolveByzantineCount(HS,
                ByzantineConfig.AttackType.NONE, Map.of("allow-beyond-bft-threshold", "true"), List.of(4)));
        // =false e a ausencia nao mudam nada sem ataque
        assertEquals(0, MVPComparison.resolveByzantineCount(HS, null, Map.of("allow-beyond-bft-threshold", "false"), List.of(4)));
        assertEquals(0, MVPComparison.resolveByzantineCount(HS, null, Map.of(), List.of(4)));
        // sem HOTSTUFF: ignorada (aviso no stderr), como --attack/--byzantine-count
        assertEquals(0, MVPComparison.resolveByzantineCount(List.of("MCO2"), null,
                Map.of("allow-beyond-bft-threshold", "true"), List.of(4)));
        assertTrue(MVPComparison.knownFlags().contains("allow-beyond-bft-threshold"));
    }

    private static String[] hsArgs(Path out, int n, String count, String allow) {
        List<String> a = new ArrayList<>(List.of("--protocols=HOTSTUFF", "--validators=" + n, "--trials=1", "--duration=10",
                "--seed=1", "--output=" + out, "--csv-out=" + out.resolve("mvp-results.csv"),
                "--hs-view-timeout=10.0", "--hs-max-future-view-horizon=1000", "--hs-batch-size=20", "--hs-tx-size=512",
                "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96", "--hs-aggregated-share-size=96",
                "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL", "--hs-leader-policy=ROTATING",
                "--hs-voter-encoding=BITMAP", "--attack=EQUIVOCATION", "--byzantine-count=" + count));
        if (allow != null) {
            a.add("--allow-beyond-bft-threshold=" + allow);
        }
        return a.toArray(new String[0]);
    }

    @Test
    void mainSemFlagForaDaFaixaFalhaAntesDeCriarSaida(@TempDir Path tmp) {
        Path out = tmp.resolve("sem-flag");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MVPComparison.main(hsArgs(out, 4, "2", null)));
        assertTrue(e.getMessage().contains("n=4 f=2 limite floor((n-1)/3)=1"), e.getMessage());
        assertTrue(Files.notExists(out), "falha antes de criar saida");
    }

    @Test
    void mainDentroDaFaixaComEmSemFlagTemSaidaIdentica(@TempDir Path tmp) throws IOException {
        Path sem = tmp.resolve("sem");
        Path com = tmp.resolve("com");
        MVPComparison.main(hsArgs(sem, 4, "1", null));
        MVPComparison.main(hsArgs(com, 4, "1", "true"));
        assertEquals(Files.readString(sem.resolve("mvp-results.csv")), Files.readString(com.resolve("mvp-results.csv")));
        assertTrue(!Files.readString(com.resolve("mvp-results.csv")).contains("fora_da_faixa"));
        assertTrue(!Files.readString(com.resolve("mvp-results-cb.csv")).contains("fora_da_faixa"));
    }

    /**
     * n=7, f=3 (limite 2): o n=4 com f=2 NAO serve aqui porque a precondicao do ataque (EQUIVOCATION exige destinatario
     * honesto em cada metade dos vizinhos, HotStuff.requireEquivocationSplit) aborta a simulacao: isso e do ataque, nao
     * da flag. O n=4/f=2 fica coberto no nivel de unidade e em {@link #mainSemFlagForaDaFaixaFalhaAntesDeCriarSaida}.
     */
    @Test
    void mainForaDaFaixaComFlagRodaERotulaOsCsv(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("com-flag");
        MVPComparison.main(hsArgs(out, 7, "3", "true"));
        List<String> agg = Files.readAllLines(out.resolve("mvp-results.csv"));
        assertTrue(agg.get(0).startsWith("# attack=EQUIVOCATION byzantine_count=3"), agg.get(0));
        assertTrue(agg.get(1).startsWith("# fora_da_faixa=true n=7 byzantine_count=3 limite_bft=2"), agg.get(1));
        assertEquals("project;n;trials;Tb_avg;Tb_std;Cb_avg;Cb_std;Tt_avg;Tt_std", agg.get(2));
        assertTrue(agg.get(3).startsWith("HOTSTUFF;7;1;"), agg.get(3));
        assertTrue(Files.readAllLines(out.resolve("mvp-results-cb.csv")).stream()
                .anyMatch(l -> l.startsWith("# fora_da_faixa=true n=7 ")));
    }
}
