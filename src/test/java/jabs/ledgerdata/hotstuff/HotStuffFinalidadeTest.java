package jabs.ledgerdata.hotstuff;

import jabs.network.message.HotStuffProposalMessage;
import jabs.network.message.HotStuffQcNetworkMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Representacao invalida por heranca: o tamanho (BasicData#getSize, sobrescrevivel) e as invariantes de fase sao
 * validados na construcao, entao uma subclasse poderia alterar o que foi validado. Por isso as classes concretas do
 * dominio HotStuff sao {@code final}; a unica excecao e {@link HotStuffBlock}, estendida de fato por
 * {@link HotStuffBlockWithTx}, que e {@code sealed} com essa unica subclasse permitida (e ela propria final).
 */
class HotStuffFinalidadeTest {

    private static final List<Class<?>> FINAIS = List.of(
            HotStuffQuorumCertificate.class,
            HotStuffQcMessage.class,
            HotStuffVote.class,
            HotStuffQcNetworkMessage.class,
            HotStuffProposalMessage.class,
            HotStuffBlockWithTx.class,
            HotStuffTx.class);

    @Test
    void classesConcretasDoDominioHotStuffSaoFinais() {
        for (Class<?> c : FINAIS) {
            assertTrue(Modifier.isFinal(c.getModifiers()), c.getName() + " deve ser final");
        }
    }

    @Test
    void hotStuffBlockEhSeladoComAUnicaSubclasseFinalHotStuffBlockWithTx() {
        assertFalse(Modifier.isFinal(HotStuffBlock.class.getModifiers()),
                "HotStuffBlock e estendido por HotStuffBlockWithTx: nao pode ser final");
        assertTrue(HotStuffBlock.class.isSealed(), "HotStuffBlock deve ser sealed");
        assertEquals(List.of(HotStuffBlockWithTx.class),
                Arrays.asList(HotStuffBlock.class.getPermittedSubclasses()));
        assertTrue(Modifier.isFinal(HotStuffBlockWithTx.class.getModifiers()));
    }

    @Test
    void nenhumaSubclasseNoClasspathDoProjetoAlemDaPermitida() throws Exception {
        List<Class<?>> alvos = new ArrayList<>(FINAIS);
        alvos.add(HotStuffBlock.class);

        List<String> violacoes = new ArrayList<>();
        for (Class<?> carregada : classesDoProjeto()) {
            Class<?> pai = carregada.getSuperclass();
            if (pai == null || !alvos.contains(pai)) continue;
            boolean permitida = pai == HotStuffBlock.class && carregada == HotStuffBlockWithTx.class;
            if (!permitida) violacoes.add(carregada.getName() + " estende " + pai.getName());
        }
        assertTrue(violacoes.isEmpty(), "subclasses nao permitidas: " + violacoes);
    }

    @Test
    void varredura_enxergaAsClassesDoProjeto() throws Exception {
        List<String> nomes = classesDoProjeto().stream().map(Class::getName).collect(Collectors.toList());
        assertTrue(nomes.contains(HotStuffBlockWithTx.class.getName()), "a varredura nao achou as classes de main");
        assertTrue(nomes.contains(HotStuffFinalidadeTest.class.getName()), "a varredura nao achou as classes de teste");
    }

    /** Todas as classes de target/classes e target/test-classes (as raizes de HotStuffBlock e deste teste). */
    private static List<Class<?>> classesDoProjeto() throws IOException, URISyntaxException, ClassNotFoundException {
        List<Path> raizes = List.of(
                Paths.get(HotStuffBlock.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Paths.get(HotStuffFinalidadeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        List<Class<?>> out = new ArrayList<>();
        for (Path raiz : raizes) {
            assertTrue(Files.isDirectory(raiz), "raiz do classpath nao e diretorio: " + raiz);
            List<Path> arquivos;
            try (Stream<Path> s = Files.walk(raiz)) {
                arquivos = s.filter(p -> p.toString().endsWith(".class")).sorted().collect(Collectors.toList());
            }
            for (Path arq : arquivos) {
                String rel = raiz.relativize(arq).toString();
                String nome = rel.substring(0, rel.length() - ".class".length()).replace('/', '.').replace('\\', '.');
                if (nome.equals("module-info") || nome.endsWith("package-info")) continue;
                out.add(Class.forName(nome, false, HotStuffBlock.class.getClassLoader()));
            }
        }
        return out;
    }
}
