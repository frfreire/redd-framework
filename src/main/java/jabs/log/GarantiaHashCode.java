package jabs.log;

import java.lang.management.ManagementFactory;

import com.sun.management.HotSpotDiagnosticMXBean;

/**
 * Impoe, nas execucoes reais que ligam o registro de carga do enlace (ADR-0012, M1a-1), a condicao da garantia de nao
 * regressao: a JVM tem de estar com {@code -XX:+UnlockExperimentalVMOptions -XX:hashCode=2} (decisao humana de 12/11:
 * a simulacao NAO foi tornada independente da ordem de hash; a garantia "ligar o registro nao altera nenhuma saida
 * existente" so vale com o hash de identidade fixado).
 *
 * <p>FALHA ALTO: {@link #exigir()} lanca {@link IllegalStateException} ("GARANTIA INVALIDA ...") se o valor efetivo
 * de {@code hashCode} lido da JVM em execucao nao for {@code 2}, ou se ele nao puder ser lido (JVM que nao e HotSpot).
 * Nao ha opcao de assumir o risco. Chamada SOMENTE quando o registro esta ligado ({@code --load-record=true} em
 * {@code MVPComparison}, e em {@code LinkLoadRecorder.initialLog}); com o registro desligado esta classe nem e
 * carregada.
 *
 * <p>Esta classe vive separada de {@code LinkLoadRecorder} de proposito: a varredura estatica RM-08 daquele arquivo
 * proibe o token de hash no codigo da instrumentacao, e aqui o nome da opcao da JVM e uma string de consulta, nao uma
 * chamada de hash. Nada aqui chama {@code hashCode()}/{@code identityHashCode}, usa colecao hash ou
 * {@code RandomnessEngine}, nem agenda evento.
 */
public final class GarantiaHashCode {
    /** Nome da opcao HotSpot consultada. */
    public static final String OPCAO = "hashCode";

    /** Valor exigido. */
    public static final String VALOR_EXIGIDO = "2";

    /** Flags que o usuario deve passar (nesta ordem: a de desbloqueio vem antes). */
    public static final String FLAGS = "-XX:+UnlockExperimentalVMOptions -XX:hashCode=2";

    private GarantiaHashCode() {
    }

    /**
     * Valor efetivo de {@code hashCode} na JVM em execucao, ou {@code null} se nao puder ser lido (JVM sem
     * {@code HotSpotDiagnosticMXBean} ou opcao inexistente).
     */
    public static String efetivo() {
        try {
            HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            return bean.getVMOption(OPCAO).getValue();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Funcao pura (testavel sem JVM filha): mensagem de erro para o valor efetivo dado, ou {@code null} se ele
     * satisfaz a garantia.
     *
     * @param valorEfetivo valor lido da JVM, ou {@code null} se nao foi possivel le-lo
     */
    public static String mensagemSeInvalido(String valorEfetivo) {
        if (VALOR_EXIGIDO.equals(valorEfetivo)) {
            return null;
        }
        String lido = valorEfetivo == null ? "nao foi possivel ler o valor na JVM em execucao" : "efetivo=" + valorEfetivo;
        return "GARANTIA INVALIDA: o registro de carga do enlace (--load-record=true) so tem garantia de nao regressao"
                + " com o hash de identidade da JVM fixado, e esta JVM nao o tem (hashCode " + lido + ", exigido="
                + VALOR_EXIGIDO + "). Passe " + FLAGS + " na linha de comando da JVM (antes de -cp), nas execucoes"
                + " das baterias tambem (ex.: scripts/jabs/nao_regressao.py --jvm-flag=-XX:+UnlockExperimentalVMOptions"
                + " --jvm-flag=-XX:hashCode=2). " + descricaoJvm();
    }

    /** Falha alto se a JVM em execucao nao tem {@code hashCode=2} efetivo. */
    public static void exigir() {
        String msg = mensagemSeInvalido(efetivo());
        if (msg != null) {
            throw new IllegalStateException(msg);
        }
    }

    /** Identificacao da JVM para o log de reprodutibilidade (so propriedades do sistema; sem hash). */
    public static String descricaoJvm() {
        return "java.runtime.version=" + System.getProperty("java.runtime.version") + "; java.vm.name="
                + System.getProperty("java.vm.name") + "; java.vm.version=" + System.getProperty("java.vm.version")
                + "; java.vendor=" + System.getProperty("java.vendor");
    }

    /** Linha de reprodutibilidade com o valor efetivo verificado (chamar depois de {@link #exigir()}). */
    public static String linhaReprodutibilidade() {
        return "hashCode=" + efetivo() + " (verificado na JVM em execucao; exigido=" + VALOR_EXIGIDO + "); "
                + descricaoJvm();
    }
}
