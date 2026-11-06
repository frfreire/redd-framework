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
 * <p><b>Por que {@code hashCode=2}.</b> Ligar o registro carrega classes novas (LinkLoadRecorder, LinkLoadCounters,
 * Result, PriorityQueue$Itr) e cada carga de classe na thread principal avanca a sequencia de
 * {@code System.identityHashCode}. Nos protocolos que iteram colecoes hash chaveadas por objeto sem hashCode (Casper,
 * Parlia, Nakamoto: debito 5 do ADR-0006) isso muda o resultado; no modo de hash constante nao muda. Sem a flag a
 * diferenca existe e nao e causada pela logica da instrumentacao (ver o relatorio da rodada 2 da M1a-1).
 *
 * <p><b>Pre-condicao verificada, nao presumida:</b> o teste exige que a coluna {@code BlockHashCode} do CSV seja
 * constante (valor 1). Se a JVM ignorar a flag, a pre-condicao falha em vez de o teste passar ou falhar por engano.
 */
class RegistroLigadoNaoRegressaoTest {
    @TempDir
    Path tmp;

    private static String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private void rodar(Path out, String proto, String n, boolean ligado) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2", "-cp",
                classpath(), "jabs.example.MVPComparison", "--protocols=" + proto, "--validators=" + n, "--trials=1",
                "--duration=60", "--seed=1", "--output=" + out));
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
                assertEquals("1", v[col], caso + ": -XX:hashCode=2 nao esta efetivo (BlockHashCode deveria ser 1)");
                linhas++;
            }
            totalLinhas += linhas;
        }
        return totalLinhas;
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
