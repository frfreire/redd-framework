package jabs.scenario;

import jabs.network.networks.hotstuff.HotStuffTestConfig;

/**
 * Ferramenta de evidencia (nao e teste): roda o {@link HotStuffLANScenario} com a config de TESTE e grava o CSV.
 * Uso: {@code HotStuffCsvMain <semente> <n> <duracao_s> <saida.csv>}. Serve para comparar a saida antes/depois de
 * uma mudanca (ex.: D3a-2a-2c-3: o timer de visao nao pode mudar a saida do caminho honesto). Valores de teste, nao
 * de pesquisa.
 */
public final class HotStuffCsvMain {
    private HotStuffCsvMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("uso: HotStuffCsvMain <semente> <n> <duracao_s> <saida.csv>");
        }
        long seed = Long.parseLong(args[0]);
        int n = Integer.parseInt(args[1]);
        double duracao = Double.parseDouble(args[2]);
        HotStuffLANScenario scenario = new HotStuffLANScenario("hotstuff-csv", seed, n, duracao,
                HotStuffTestConfig.config());
        scenario.addMetricsLogger(args[3]);
        scenario.run();
    }
}
