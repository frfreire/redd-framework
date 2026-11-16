#!/usr/bin/env python3
"""EMENDA 1b (16/11/2026): gera harness/ObservadorVal.java a partir do ObservadorHotStuff.java PINADO do piloto (sha256 conferido),
SO acrescentando a escrita de obs/pacotes-por-altura.csv.gz (pacotes e bytes entregues pela rede por altura, somados sobre
todos os nos, janela de regime IDENTICA a CbRedeMedida: alturas FIRST_REGIME_HEIGHT..hmax-LAST_IN_FLIGHT).
Nenhuma outra linha muda (exceto o nome da classe). O arquivo do piloto NAO e alterado. Recusa sobrescrever.
Fonte dos pacotes: NetworkHeightLedger.Cell.getPackets() (API publica do simulador pinado; conta pacotes entregues pela rede
simulada que nomeiam a altura, sem corte local, nunca autoentrega).
Uso: gerar-observador-val.py [--compilar-teste]   (compila SO em /tmp/opencode/val-obs-teste contra o build de fumaca; nao simula)
"""
import hashlib
import subprocess
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
ORIG = RAIZ / "experimentos/piloto-timeout-hotstuff/harness/ObservadorHotStuff.java"
SHA_ORIG = "6abfcd570435773642293a2aa4128e319ea8565fae1ae87c565e2e197c3270b4"
DEST = VAL / "harness" / "ObservadorVal.java"
MARCA_USO = "    static String esc(String s)"
MARCA_CHAMADA = "        // por altura (CSV gz): so alturas finalizadas por pelo menos um no"

METODO = '''    /**
     * EMENDA 1b (V-A, contagem de mensagens/autenticadores): pacotes e bytes entregues pela rede simulada por altura,
     * somados sobre TODOS os nos receptores, na janela de regime de CbRedeMedida (2..hmax-2; hmax = maior altura finalizada
     * por todos os nos). So le o ledger publico (NetworkHeightLedger); nao altera a simulacao.
     */
    @SuppressWarnings({"rawtypes"})
    static void escreverPacotesPorAltura(HotStuffLANScenario scenario, Path outDir) throws IOException {
        java.util.List<jabs.metrics.NetworkHeightLedger> ledgers = new java.util.ArrayList<>();
        for (Object o : scenario.getNetwork().getAllNodes()) {
            ledgers.add(((HotStuff) ((jabs.network.node.nodes.PeerBlockchainNode) o).getConsensusAlgorithm()).getHeightLedger());
        }
        long hmax = 0;
        for (Long h : ledgers.get(0).heights().keySet()) {
            boolean todos = true;
            for (jabs.metrics.NetworkHeightLedger l : ledgers) {
                jabs.metrics.NetworkHeightLedger.Cell c = l.heights().get(h);
                if (c == null || !c.isFinalized()) { todos = false; break; }
            }
            if (todos && h > hmax) hmax = h;
        }
        long lo = jabs.metrics.CbRedeMedida.FIRST_REGIME_HEIGHT;
        long hi = hmax - jabs.metrics.CbRedeMedida.LAST_IN_FLIGHT;
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(
                Files.newOutputStream(outDir.resolve("pacotes-por-altura.csv.gz"))), StandardCharsets.UTF_8))) {
            w.write("# janela de regime: alturas " + lo + ".." + hi + " (hmax=" + hmax + "); soma sobre os " + ledgers.size()
                    + " nos receptores; pacotes entregues pela rede simulada, sem autoentrega\\n");
            w.write("height,pacotes,bytes_total,bytes_consenso\\n");
            for (long h = lo; h <= hi; h++) {
                long p = 0, bt = 0, bc = 0;
                for (jabs.metrics.NetworkHeightLedger l : ledgers) {
                    jabs.metrics.NetworkHeightLedger.Cell c = l.heights().get(h);
                    if (c != null) { p += c.getPackets(); bt += c.getTotalBytes(); bc += c.getConsensusBytes(); }
                }
                w.write(h + "," + p + "," + bt + "," + bc + "\\n");
            }
        }
    }

'''


def main():
    src = ORIG.read_bytes()
    h = hashlib.sha256(src).hexdigest()
    if h != SHA_ORIG:
        sys.exit(f"observador do piloto ({h}) != pinado ({SHA_ORIG})")
    if DEST.exists():
        sys.exit(f"{DEST.name} ja existe; nao sobrescrevo")
    txt = src.decode("utf-8")
    assert txt.count(MARCA_USO) == 1 and txt.count(MARCA_CHAMADA) == 1
    txt = txt.replace(MARCA_USO, METODO + MARCA_USO)
    txt = txt.replace(MARCA_CHAMADA, "        escreverPacotesPorAltura(scenario, outDir);   // EMENDA 1b\n" + MARCA_CHAMADA)
    txt = txt.replace("ObservadorHotStuff", "ObservadorVal")
    DEST.parent.mkdir(exist_ok=True)
    DEST.write_text(txt, encoding="utf-8")
    print("gerado", DEST, hashlib.sha256(DEST.read_bytes()).hexdigest())


def compilar_teste():
    base = Path("/tmp/opencode/val-hs-fumaca")
    out = Path("/tmp/opencode/val-obs-teste")
    out.mkdir(parents=True, exist_ok=True)
    cp = f"{base}/target/classes:" + (base / "cp.txt").read_text().strip()
    r = subprocess.run(["javac", "-nowarn", "-cp", cp, "-d", str(out), str(DEST)], capture_output=True, text=True)
    print("javac rc =", r.returncode, r.stderr[-1500:])
    sys.exit(r.returncode)


if __name__ == "__main__":
    if sys.argv[1:] == ["--compilar-teste"]:
        compilar_teste()
    main()
