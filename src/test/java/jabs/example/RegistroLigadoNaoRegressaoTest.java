package jabs.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0012 RM-08, criterio 7 (nao regressao) com o registro LIGADO: rodando {@code MVPComparison} com e sem
 * {@code --load-record=true --load-record-t0=10} (mesma semente, JVM nova, {@code -XX:hashCode=2}), TODOS os arquivos
 * que a execucao sem registro produz tem de sair identicos byte a byte; o ligado so pode ACRESCENTAR
 * {@code *-nos.csv} e {@code *-corrida.csv}.
 *
 * <h2>ESCOPO DA GARANTIA (decisao humana de 12/11, M1a-1 rodada 2)</h2>
 * A garantia "ligar o registro nao altera a saida" vale <b>somente com {@code -XX:+UnlockExperimentalVMOptions
 * -XX:hashCode=2} fixado na JVM da execucao</b>, que e a condicao de reprodutibilidade das baterias. Ela
 * <b>NAO</b> e afirmada para uma JVM com o hashCode padrao (identidade por endereco/PRNG de thread): la, ligar o
 * registro carrega classes novas e desloca a sequencia de {@code System.identityHashCode}, o que muda a saida dos
 * protocolos que iteram colecoes hash chaveadas por objeto sem {@code hashCode} (Casper, Parlia, Nakamoto: debito 5
 * do ADR-0006). Esse deslocamento e do codigo ja existente, nao da logica do registro (que nao chama
 * {@code hashCode}/{@code identityHashCode}, nao usa colecao hash nem {@code RandomnessEngine}), mas por isso uma
 * execucao com hash padrao <b>nao e reproduzivel</b> nem com o registro desligado, e nao ha o que comparar. O teste
 * propositalmente NAO foi tornado independente da ordem de hash.
 *
 * <p>Onde a garantia e invocada (uma bateria que liga o registro) a mesma flag e obrigatoria. O mecanismo que ja
 * existe para isso e {@code scripts/jabs/nao_regressao.py --jvm-flag=-XX:+UnlockExperimentalVMOptions
 * --jvm-flag=-XX:hashCode=2} (e {@code nr_hscb_fase2.py}); {@code scripts/jabs/checar-determinismo.py} NAO fixa o
 * hashCode e portanto nao exercita esta garantia.
 *
 * <h2>Como a condicao e imposta (nao so dita)</h2>
 * <ol>
 *   <li>{@link #hashCodeFixadoNaJvmDasExecucoes()}: antes de qualquer comparacao, consulta a JVM filha com a MESMA
 *       lista de flags do teste ({@code -XX:+PrintFlagsFinal}), exige {@code hashCode = 2} efetivo e falha com a
 *       mensagem "GARANTIA INVALIDA" caso contrario. Tambem escreve em stdout o {@code java -version} e o valor
 *       efetivo de {@code hashCode} (registro de reprodutibilidade no log do surefire).</li>
 *   <li>Em cada caso, a coluna {@code BlockHashCode} do CSV tem de ser constante (valor 1): prova, na saida real,
 *       que a flag valeu na execucao comparada.</li>
 * </ol>
 * Nenhuma saida EXISTENTE do simulador foi alterada para isso (o cabecalho dos CSV existentes nao ganha campo novo).
 * Rodada 3: alem desta verificacao no teste, a condicao e IMPOSTA em execucao real: ligar o registro numa JVM sem
 * {@code hashCode=2} falha alto ({@code GarantiaHashCode}, provado em {@code GarantiaHashCodeTest}); os dois arquivos
 * NOVOS do registro ganham uma linha {@code # JVM:} com o valor efetivo e a versao do Java.
 */
class RegistroLigadoNaoRegressaoTest {
    @TempDir
    Path tmp;

    /** Flags de JVM que fixam o hash de identidade; as MESMAS na consulta de pre-condicao e nas execucoes. */
    private static final List<String> FLAGS_HASH = List.of("-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2");

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private void rodar(Path out, String proto, String n, boolean ligado) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java()));
        cmd.addAll(FLAGS_HASH);
        cmd.addAll(List.of("-cp", classpath(), "jabs.example.MVPComparison", "--protocols=" + proto,
                "--validators=" + n, "--trials=1", "--duration=60", "--seed=1", "--output=" + out));
        if (ligado) {
            cmd.add("--load-record=true");
            cmd.add("--load-record-t0=10");
        }
        Path so = tmp.resolve(out.getFileName() + ".stdout");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(so.toFile()).start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly().waitFor();
            throw new IllegalStateException("JVM filha estourou 180 s: " + cmd);
        }
        assertEquals(0, p.exitValue(), "JVM filha: " + Files.readString(so, StandardCharsets.UTF_8));
    }

    private static Map<String, byte[]> arquivos(Path dir) throws Exception {
        Map<String, byte[]> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                m.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return m;
    }

    /** @return numero de linhas de bloco vistas (MCO2:10:60 nao finaliza bloco em 60 s: so cabecalho, 0 linhas) */
    private static int exigeHashConstante(Map<String, byte[]> arquivos, String caso) {
        int totalLinhas = 0;
        for (Map.Entry<String, byte[]> e : arquivos.entrySet()) {
            if (!e.getKey().endsWith("v-t0.csv")) {
                continue;
            }
            int col = -1;
            int linhas = 0;
            for (String l : new String(e.getValue(), StandardCharsets.UTF_8).split("\\R")) {
                if (l.isBlank() || l.startsWith("#")) {
                    continue;
                }
                String[] v = l.split(",", -1);
                if (col < 0) {
                    col = Arrays.asList(v).indexOf("BlockHashCode");
                    assertTrue(col >= 0, caso + ": sem coluna BlockHashCode");
                    continue;
                }
                assertEquals("1", v[col], caso + ": GARANTIA INVALIDA: -XX:hashCode=2 nao esta efetivo na execucao"
                        + " (BlockHashCode deveria ser 1). A garantia 'ligar o registro nao altera a saida' so vale com o"
                        + " hashCode fixado (ver o javadoc desta classe)");
                linhas++;
            }
            totalLinhas += linhas;
        }
        return totalLinhas;
    }

    /**
     * Valor efetivo de {@code hashCode} num trecho de {@code -XX:+PrintFlagsFinal}, ou {@code null} se a linha nao
     * existe. Formato: {@code "     int hashCode   = 2   {experimental} {command line}"}.
     */
    static Integer hashCodeEfetivo(String printFlagsFinal) {
        Matcher m = Pattern.compile("(?m)^\\s*\\w+\\s+hashCode\\s*=\\s*(-?\\d+)").matcher(printFlagsFinal);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    @Test
    void analisadorDoHashCodeEfetivoDistingueFixadoDeNaoFixado() {
        assertEquals(2, hashCodeEfetivo("     int hashCode                                  = 2                                {experimental} {command line}\n"));
        assertEquals(5, hashCodeEfetivo("x\n     intx hashCode = 5 {experimental} {default}\n"));
        assertEquals(null, hashCodeEfetivo("     int hashCodeFoo = 2\n     int other = 2\n"));
        assertEquals(null, hashCodeEfetivo(""));
    }

    /**
     * Pre-condicao imposta: a JVM que executa as comparacoes tem {@code hashCode=2} efetivo. Falha com mensagem
     * clara, e registra {@code java -version} e o valor efetivo no stdout (reprodutibilidade).
     */
    @Test
    void hashCodeFixadoNaJvmDasExecucoes() throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java()));
        cmd.addAll(FLAGS_HASH);
        cmd.addAll(List.of("-XX:+PrintFlagsFinal", "-version"));
        Path so = tmp.resolve("flags-finais.txt");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(so.toFile()).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "JVM filha de consulta estourou 60 s: " + cmd);
        String saida = Files.readString(so, StandardCharsets.UTF_8);
        assertEquals(0, p.exitValue(), "JVM filha rejeitou as flags " + FLAGS_HASH + ": " + saida);
        Integer efetivo = hashCodeEfetivo(saida);
        assertEquals(Integer.valueOf(2), efetivo, "GARANTIA INVALIDA: a JVM das execucoes nao tem -XX:hashCode=2 efetivo"
                + " (efetivo=" + efetivo + "). 'Ligar o registro nao altera a saida' so vale com o hashCode fixado, que e a"
                + " condicao de reprodutibilidade das baterias (ver o javadoc desta classe; nas baterias use"
                + " scripts/jabs/nao_regressao.py --jvm-flag=-XX:+UnlockExperimentalVMOptions --jvm-flag=-XX:hashCode=2)");
        String versao = saida.lines().filter(l -> l.contains("version") || l.startsWith("OpenJDK") || l.startsWith("Java("))
                .filter(l -> !l.contains("hashCode")).collect(Collectors.joining(" | "));
        System.out.println("[reprodutibilidade] flags=" + FLAGS_HASH + " hashCode_efetivo=" + efetivo + " java=" + versao);
    }

    @Test
    void ligadoSoAcrescentaArquivosEMantemOsExistentesIdenticosComHashConstante() throws Exception {
        String[][] casos = {{"AMBIFY", "10"}, {"MCO2", "10"}, {"ARQ", "7"}};
        for (String[] c : casos) {
            Path off = tmp.resolve(c[0] + "-off");
            Path on = tmp.resolve(c[0] + "-on");
            rodar(off, c[0], c[1], false);
            rodar(on, c[0], c[1], true);
            Map<String, byte[]> fo = arquivos(off);
            Map<String, byte[]> fl = arquivos(on);
            int blocosOff = exigeHashConstante(fo, c[0] + " desligado");
            int blocosOn = exigeHashConstante(fl, c[0] + " ligado");
            if (!c[0].equals("MCO2")) {
                assertTrue(blocosOff > 0 && blocosOn > 0, c[0] + ": sem linhas de bloco: a pre-condicao nao se aplica");
            }
            for (Map.Entry<String, byte[]> e : fo.entrySet()) {
                assertTrue(fl.containsKey(e.getKey()), c[0] + ": o ligado perdeu " + e.getKey());
                assertTrue(Arrays.equals(e.getValue(), fl.get(e.getKey())),
                        c[0] + ": " + e.getKey() + " difere entre desligado e ligado (RM-08)");
            }
            List<String> novos = new ArrayList<>(fl.keySet());
            novos.removeAll(fo.keySet());
            assertEquals(2, novos.size(), c[0] + ": arquivos novos = " + novos);
            for (String k : novos) {
                assertTrue(k.endsWith("-nos.csv") || k.endsWith("-corrida.csv"), c[0] + ": arquivo novo inesperado " + k);
            }
            assertFalse(fo.keySet().stream().anyMatch(k -> k.endsWith("-nos.csv") || k.endsWith("-corrida.csv")),
                    c[0] + ": o desligado nao pode gerar arquivos do registro");
        }
    }
}
