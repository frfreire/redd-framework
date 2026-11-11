package jabs.example;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Fixture canonica de lote e tx-size para HOTSTUFF e TENDERMINT, lida de
 * {@code /fixture/fixture-canonica.properties} (o mesmo arquivo que {@code scripts/jabs/fixture_canonica.py} le).
 * FIXTURE DE TESTE: nao e calibracao nem valor de pesquisa (a definir com o humano).
 */
final class FixtureCanonica {
    static final String RECURSO = "/fixture/fixture-canonica.properties";
    static final int BATCH_SIZE;
    static final int TX_SIZE;

    static {
        Properties p = new Properties();
        try (InputStream in = FixtureCanonica.class.getResourceAsStream(RECURSO)) {
            if (in == null) {
                throw new IllegalStateException("fixture canonica ausente: " + RECURSO);
            }
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("fixture canonica ilegivel: " + RECURSO, e);
        }
        BATCH_SIZE = Integer.parseInt(p.getProperty("batch-size").trim());
        TX_SIZE = Integer.parseInt(p.getProperty("tx-size").trim());
    }

    private FixtureCanonica() {
    }

    /** As flags de lote/tx de HOTSTUFF e TENDERMINT com os valores canonicos. */
    static String[] flagsLoteETx() {
        return new String[] {"--hs-batch-size=" + BATCH_SIZE, "--hs-tx-size=" + TX_SIZE,
                "--tm-batch-size=" + BATCH_SIZE, "--tm-tx-size=" + TX_SIZE};
    }
}
