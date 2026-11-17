#!/usr/bin/env python3
r"""D2 (ADR-0015, registro de 10/11/2026): MEDE a autoentrega por protocolo numa COPIA instrumentada do HEAD.

Autoentrega = pacote cujo remetente e o proprio no receptor (Packet.getFrom() == this) chegando a
PeerBlockchainNode.processIncomingPacket. O instrumento so CONTA (por classe de consenso, tipo de mensagem e
caminho: 'direto' = chamada de processIncomingPacket sem passar por PacketReceivingProcess; 'via-rede' = passou pela
fila de envio, latencia e fila de recepcao do simulador). Nao altera a simulacao. Nao toca a arvore de trabalho:
a copia vive em --copia (padrao /tmp/opencode/d2-medida), construida do git (construir-base-head.py).

Uso:
  medir-autoentrega.py [--commit HEAD] [--copia /tmp/opencode/d2-medida] [--saida /tmp/opencode/d2-medida-saida]
Saida: tabela 'MEDIDA <consenso> <mensagem> <caminho> pacotes=.. bytes=..' por protocolo e n. Exit 0 ok, 2 erro.
Nao e prova de corretude: e o inventario medido (um contador, rodada curta) pedido pela tarefa D2.
"""
import argparse
import subprocess
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
ANCORA = "    public void processIncomingPacket(Packet packet) {\n        Message message = packet.getMessage();\n"
INSTR = ANCORA + """        if (packet.getFrom() == this) {
            // via-rede = o CHAMADOR IMEDIATO e PacketReceivingProcess (o pacote percorreu fila de envio, latencia e
            // fila de recepcao). Chamada direta aninhada num evento de recepcao tambem tem o processo na pilha: nao conta.
            StackTraceElement[] st = new Throwable().getStackTrace();
            boolean viaRede = st.length > 1 && st[1].getClassName().endsWith("PacketReceivingProcess");
            String k = consensusAlgorithm.getClass().getSimpleName() + " " + message.getClass().getSimpleName()
                    + (message instanceof DataMessage ? "/" + ((DataMessage) message).getData().getClass().getSimpleName() : "")
                    + " " + (viaRede ? "via-rede" : "direto");
            synchronized (MEDIDA) { long[] v = MEDIDA.computeIfAbsent(k, x -> new long[2]); v[0]++; v[1] += packet.getSize(); }
        }
"""
CAMPO = ("    public static final java.util.TreeMap<String, long[]> MEDIDA = new java.util.TreeMap<>();\n"
         "    static { Runtime.getRuntime().addShutdownHook(new Thread(() -> { synchronized (MEDIDA) {\n"
         "        for (java.util.Map.Entry<String, long[]> e : MEDIDA.entrySet())\n"
         "            System.err.println(\"MEDIDA \" + e.getKey() + \" pacotes=\" + e.getValue()[0] + \" bytes=\" + e.getValue()[1]);\n"
         "    } })); }\n\n    @Override\n")
TM = ["--tm-propose-timeout=1.0", "--tm-propose-delta=0.5", "--tm-prevote-timeout=1.0", "--tm-prevote-delta=0.5",
      "--tm-precommit-timeout=1.0", "--tm-precommit-delta=0.5", "--tm-batch-size=20", "--tm-tx-size=512",
      "--tm-signature-size=64", "--tm-proposer-signature-size=80", "--tm-message-overhead=10"]


def sh(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write("falhou: %s\n%s\n%s\n" % (" ".join(cmd), r.stdout[-2000:], r.stderr[-2000:]))
        sys.exit(2)
    return r


CASOS = [("ARQ", 7, 60), ("MCO2", 10, 1200), ("AMBIFY", 10, 600), ("OFFSET", 40, 20000),
         ("DAGSPER", 10, 600), ("HOTSTUFF", 4, 30), ("TENDERMINT", 4, 30)]
HS = ["--hs-view-timeout=10.0", "--hs-max-future-view-horizon=1000", "--hs-batch-size=20", "--hs-tx-size=512",
      "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96", "--hs-aggregated-share-size=96",
      "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL", "--hs-leader-policy=ROTATING",
      "--hs-voter-encoding=BITMAP"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--commit", default="HEAD")
    ap.add_argument("--copia", default="/tmp/opencode/d2-medida")
    ap.add_argument("--saida", default="/tmp/opencode/d2-medida-saida")
    ap.add_argument("--semente", type=int, default=1)
    a = ap.parse_args()
    copia = Path(a.copia)
    if not (copia / "COMMIT").exists():
        sh([sys.executable, str(AQUI / "construir-base-head.py"), "--commit", a.commit, "--destino", str(copia)])
    alvo = copia / "src/main/java/jabs/network/node/nodes/PeerBlockchainNode.java"
    src = alvo.read_text()
    if "MEDIDA" not in src:
        if ANCORA not in src:
            sys.exit("ERRO: ancora de processIncomingPacket nao encontrada (script desatualizado)")
        antes = "    @Override\n" + ANCORA.split("\n")[0] + "\n"
        if antes not in src:
            sys.exit("ERRO: ancora @Override nao encontrada")
        src = src.replace("    @Override\n" + ANCORA, CAMPO + INSTR.replace("    public void", "    public void", 1), 1)
        alvo.write_text(src)
        sh(["mvn", "-q", "-o", "clean", "compile"], cwd=str(copia))
    if not (copia / "target/cp.txt").exists():
        sh(["mvn", "-q", "-o", "dependency:build-classpath", "-Dmdep.outputFile=target/cp.txt"], cwd=str(copia))
    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    cp = "%s:%s" % (copia / "target/classes", (copia / "target/cp.txt").read_text().strip())
    for proto, n, dur in CASOS:
        extra = HS if proto == "HOTSTUFF" else TM if proto == "TENDERMINT" else []
        out = saida / ("%s-%d" % (proto, n))
        r = subprocess.run(["java", "-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2", "-cp", cp,
                            "jabs.example.MVPComparison", "--protocols=" + proto, "--validators=%d" % n, "--trials=1",
                            "--duration=%d" % dur, "--seed=%d" % a.semente, "--output=" + str(out), *extra],
                           capture_output=True, text=True, timeout=1800)
        if r.returncode != 0:
            print("[%s n=%d] ERRO rc=%d\n%s" % (proto, n, r.returncode, r.stderr[-800:]))
            return 2
        linhas = [l for l in r.stderr.splitlines() if l.startswith("MEDIDA ")]
        print("== %s n=%d dur=%d seed=%d" % (proto, n, dur, a.semente))
        print("\n".join(linhas) if linhas else "(nenhum pacote com from == este no)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
