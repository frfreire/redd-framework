package jabs.example;

import jabs.log.GarantiaHashCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0012, M1a-1 rodada 3, criterio 7: a condicao da garantia de nao regressao ({@code -XX:+UnlockExperimentalVMOptions
 * -XX:hashCode=2}) e IMPOSTA em execucao real, nao so documentada. Ligar o registro numa JVM sem {@code hashCode=2}
 * tem de FALHAR ALTO ("GARANTIA INVALIDA ... passe -XX:+UnlockExperimentalVMOptions -XX:hashCode=2"), sem opt-out;
 * com o registro desligado nao ha verificacao e a execucao segue como antes. Cada caso roda em JVM FILHA com a lista
 * de flags escolhida pelo teste (a JVM do surefire nao serve: ela herda o {@code argLine} do pom).
 */
class GarantiaHashCodeTest {
    @TempDir
    Path tmp;

    private static final List<String> HASH2 = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2");
    private static final List<String> HASH5 = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=5");

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    /** {@code texto} = stdout + stderr (para as buscas existentes); {@code out} e {@code err} separados. */
    private record Saida(int rc, String texto, String out, String err) { }

    private Saida filha(String nome, List<String> jvm, String classe, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java()));
        cmd.addAll(jvm);
        cmd.addAll(List.of("-cp", classpath(), classe));
        cmd.addAll(List.of(args));
        Path so = tmp.resolve(nome + ".stdout");
        Path se = tmp.resolve(nome + ".stderr");
        Process p = new ProcessBuilder(cmd).redirectOutput(so.toFile()).redirectError(se.toFile()).start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            throw new IllegalStateException("JVM filha estourou 180 s: " + cmd);
        }
        String out = Files.readString(so, StandardCharsets.UTF_8);
        String err = Files.readString(se, StandardCharsets.UTF_8);
        return new Saida(p.exitValue(), out + err, out, err);
    }

    private Saida mvp(String nome, List<String> jvm, boolean registro) throws Exception {
        List<String> a = new ArrayList<>(List.of("--protocols=ARQ", "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING", "--validators=7", "--trials=1", "--duration=30",
                "--seed=1", "--output=" + tmp.resolve(nome)));
        if (registro) {
            a.add("--load-record=true");
            a.add("--load-record-t0=10");
        }
        return filha(nome, jvm, "jabs.example.MVPComparison", a.toArray(new String[0]));
    }

    private static void exigeMensagemClara(Saida s) {
        assertNotEquals(0, s.rc(), "tinha de falhar alto, saiu 0: " + s.texto());
        assertTrue(s.texto().contains("GARANTIA INVALIDA"), s.texto());
        assertTrue(s.texto().contains("-XX:+UnlockExperimentalVMOptions -XX:hashCode=2"), s.texto());
        assertTrue(s.err().contains("GARANTIA INVALIDA"), s.err());
    }

    /**
     * Rodada 4 (mains): a UNICA saida na falha e a mensagem (excecao em stderr). Stdout VAZIO: nem banner, nem linha de
     * simulacao, nem a linha [reprodutibilidade]; e nenhum aviso de flag no stderr antes da excecao.
     */
    private static void exigeFalhaSemNenhumaOutraSaida(Saida s) {
        exigeMensagemClara(s);
        // A primeira (e unica) coisa escrita no stderr e a excecao da garantia.
        assertTrue(s.err().stripLeading().startsWith("Exception in thread \"main\" java.lang.IllegalStateException: "
                + "GARANTIA INVALIDA"), s.err());
        assertEquals("", s.out(), "stdout tinha de estar VAZIO na falha (sem banner nem linha de simulacao): " + s.out());
        for (String proibido : new String[]{"╔", "MVP:", "Testing with", "Args:", "Protocol-specific", "n-semantics",
                "Network sizing", "[reprodutibilidade]", "AVISO", "FILHO-CONCLUIDO"}) {
            assertFalse(s.texto().contains(proibido), "'" + proibido + "' nao pode aparecer na falha: " + s.texto());
        }
    }

    // ---------------------------------------------------------------- funcao pura (sem JVM filha)

    @Test
    void mensagemSoQuandoOValorNaoEDois() {
        assertNull(GarantiaHashCode.mensagemSeInvalido("2"));
        for (String ruim : new String[]{"0", "1", "5", "", "02", null}) {
            String m = GarantiaHashCode.mensagemSeInvalido(ruim);
            assertNotNull(m, "valor " + ruim);
            assertTrue(m.startsWith("GARANTIA INVALIDA"), m);
            assertTrue(m.contains("-XX:+UnlockExperimentalVMOptions -XX:hashCode=2"), m);
        }
    }

    // ---------------------------------------------------------------- JVM filha: sem a flag -> falha alto

    @Test
    void mvpComRegistroLigadoSemHashCodeFalhaAltoAntesDeSimular() throws Exception {
        Saida s = mvp("mvp-semflag", List.of(), true);
        exigeFalhaSemNenhumaOutraSaida(s);
        assertFalse(Files.exists(tmp.resolve("mvp-semflag")), "nao pode ter criado saida antes de falhar");
    }

    @Test
    void mvpComRegistroLigadoSemHashCodeNaoAvisaFlagDesconhecidaAntesDeFalhar() throws Exception {
        // parseArgs avisa no stderr sobre flag desconhecida: isso tambem e saida e nao pode vir antes da garantia.
        List<String> a = List.of("--protocols=ARQ", "--pbft-max-future-horizon=1000", "--pbft-leader-policy=ROTATING", "--validators=7", "--trials=1", "--duration=30", "--seed=1",
                "--output=" + tmp.resolve("mvp-aviso"), "--flag-inexistente=1", "--load-record=true",
                "--load-record-t0=10");
        Saida s = filha("mvp-aviso", List.of(), "jabs.example.MVPComparison", a.toArray(new String[0]));
        exigeFalhaSemNenhumaOutraSaida(s);
        assertFalse(Files.exists(tmp.resolve("mvp-aviso")));
    }

    @Test
    void mvpComRegistroLigadoEHashCodeDiferenteDeDoisFalhaAlto() throws Exception {
        Saida s = mvp("mvp-hash5", HASH5, true);
        exigeFalhaSemNenhumaOutraSaida(s);
        assertTrue(s.texto().contains("efetivo=5"), s.texto());
    }

    @Test
    void cenarioComRegistroLigadoSemHashCodeFalhaAltoNoInitialLog() throws Exception {
        Saida s = filha("cenario-semflag", List.of(), "jabs.example.GarantiaHashCodeFilho",
                tmp.resolve("cen-semflag").toString());
        exigeMensagemClara(s);
        // Caminho por cenario (rede de seguranca de quem usa o LinkLoadRecorder sem o MVPComparison): so as linhas do
        // proprio AbstractScenario.run aparecem antes da excecao; nenhum banner nem linha de simulacao.
        String antes = s.texto().substring(0, s.texto().indexOf("Exception in thread"));
        for (String linha : antes.strip().split("\\R")) {
            assertTrue(linha.startsWith("Logger LinkLoadRecorder added to scenario") || linha.startsWith("Staring "),
                    "antes da excecao so pode haver as linhas do proprio AbstractScenario: " + s.texto());
        }
        assertFalse(s.texto().contains("FILHO-CONCLUIDO"), s.texto());
        assertFalse(Files.exists(tmp.resolve("cen-semflag-nos.csv")));
        assertFalse(Files.exists(tmp.resolve("cen-semflag-corrida.csv")));
    }

    // ---------------------------------------------------------------- JVM filha: com a flag -> roda e registra

    @Test
    void mvpComRegistroLigadoEHashCodeDoisRodaERegistraNoCabecalho() throws Exception {
        Saida s = mvp("mvp-hash2", HASH2, true);
        assertEquals(0, s.rc(), s.texto());
        assertTrue(s.texto().contains("[reprodutibilidade] registro de carga ligado: hashCode=2"), s.texto());
        for (String f : new String[]{"-nos.csv", "-corrida.csv"}) {
            Path csv;
            try (var st = Files.walk(tmp.resolve("mvp-hash2"))) {
                csv = st.filter(p -> p.getFileName().toString().endsWith(f)).findFirst().orElseThrow();
            }
            String cab = Files.readString(csv, StandardCharsets.UTF_8);
            assertTrue(cab.contains("# JVM: hashCode=2 (verificado na JVM em execucao; exigido=2)"), csv + ": " + cab);
            assertTrue(cab.contains("java.runtime.version="), csv.toString());
        }
    }

    @Test
    void cenarioComRegistroLigadoEHashCodeDoisRoda() throws Exception {
        Saida s = filha("cenario-hash2", HASH2, "jabs.example.GarantiaHashCodeFilho",
                tmp.resolve("cen-hash2").toString());
        assertEquals(0, s.rc(), s.texto());
        assertTrue(s.texto().contains("FILHO-CONCLUIDO"), s.texto());
        assertTrue(Files.readString(tmp.resolve("cen-hash2-nos.csv")).contains("# JVM: hashCode=2"));
    }

    // ---------------------------------------------------------------- registro desligado: nenhuma verificacao

    @Test
    void mvpComRegistroDesligadoSemHashCodeNaoVerificaENaoGeraArquivosDoRegistro() throws Exception {
        Saida s = mvp("mvp-off", List.of(), false);
        assertEquals(0, s.rc(), s.texto());
        assertFalse(s.texto().contains("GARANTIA INVALIDA"), s.texto());
        assertFalse(s.texto().contains("[reprodutibilidade]"), s.texto());
        try (var st = Files.walk(tmp.resolve("mvp-off"))) {
            assertTrue(st.noneMatch(p -> p.getFileName().toString().endsWith("-nos.csv")
                    || p.getFileName().toString().endsWith("-corrida.csv")));
        }
    }

    // ---------------------------------------------------------------- a JVM dos testes de unidade tem a condicao

    @Test
    void jvmDoSurefireTemHashCodeDois() {
        assertEquals("2", GarantiaHashCode.efetivo(), "o argLine do pom.xml deve fixar -XX:hashCode=2: "
                + GarantiaHashCode.descricaoJvm());
        GarantiaHashCode.exigir();
    }
}
