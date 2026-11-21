#!/usr/bin/env python3
r"""Prova de sensibilidade a mutacao de D2 (autoentrega fora do C_B, ADR-0015) numa COPIA em /tmp/opencode: a arvore de
trabalho NUNCA e tocada (diferente de mutar-pbft-prepare-equivocation.py, que muta no lugar e restaura).

Da raiz do repositorio:
    python3 scripts/jabs/mutar-autoentrega.py [--copia /tmp/opencode/d2-mut] [--mvn "mvn -o"] [--so M1,M5]

Passos: (1) copia src/ e pom.xml da arvore atual para --copia (recria a copia); (2) baseline na copia (DEVE passar);
(3) cada mutante e aplicado SOZINHO sobre o fonte copiado e os testes alvo DEVEM falhar ("DETECTADA"); a copia volta ao
original entre mutantes (sha256 conferido). Saida: 0 = baseline passou E todo mutante detectado; 1 = algum mutante
sobreviveu ou baseline falhou; 2 = ancora nao encontrada (script desatualizado). Reintroduz, entre outras, a contagem da
autoentrega no C_B principal (M1..M5), exatamente o que a tarefa pede.
Cobre tambem os formatos de saida aditivos de D2: *-basic-log.csv do BlockFinalizationLogger (M16..M22), s.csv do
ByzantineSweep (M23..M33) e os mutantes L7/L7b (recordSelfDelivery com > 0) e L8 (argumentos trocados no
EnhancedBlockFinalizationLogger) que sobreviveram na revisao anterior: se sobreviverem de novo, o script os NOMEIA no resumo
(saida 1); a correcao e um teste novo em src/test, nao um afrouxamento aqui.
"""
import argparse
import hashlib
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

TMP_OPENCODE = Path(tempfile.gettempdir()).resolve() / "opencode"  # no Linux: /tmp/opencode


def mvn_exe():
    """Executavel do Maven: mvn.cmd no Windows (subprocess sem shell nao resolve `mvn`), mvn fora dele."""
    if os.name == "nt":
        return shutil.which("mvn.cmd") or "mvn.cmd"
    return "mvn"


RAIZ = Path(".")
TESTES = ("AutoentregaUniformeTest,PBFTTrafficMeasurementTest,PBFTPrepareEquivocationTest,MVPComparisonAutoentregaCsvTest,"
          "ByzantineContagemUnicaSweepTest,DAGsperTrafficTest,MVPComparisonNaoRegressaoTest,"
          "BlockFinalizationLoggerAutoentregaTest,ByzantineSweepAutoentregaCsvTest,AutoentregaZeroMedidoTest,EnhancedLoggerAutoentregaConsensoTest")  # jabs.log e jabs.example (D2: CSV basico e s.csv)
J = "src/main/java/jabs/"
NODE = J + "network/node/nodes/PeerBlockchainNode.java"
BFL = J + "log/BlockFinalizationLogger.java"
SWEEP = J + "example/ByzantineSweep.java"

MUTACOES = [
    ("M1 PBFT: a autoentrega volta a ser contada no C_B principal (filtro desligado)",
     NODE, "                if (isLocalSelfDelivery(packet)) {\n                    // D2 (ADR-0015): a autoentrega NAO e C_B",
     "                if (false) {\n                    // D2 (ADR-0015): a autoentrega NAO e C_B"),
    ("M2 PBFT: a autoentrega sai do C_B mas NAO vai para a coluna propria (byte some)",
     NODE, "                    pbft.addSelfDeliveryBlockTraffic(votedBlock, packet.getSize());\n", "                    // MUTACAO: byte descartado\n"),
    ("M3 Parlia: o bloco entregue a si mesmo volta a entrar no C_B",
     NODE, "                    if (isLocalSelfDelivery(packet)) {\n                        ((Parlia<B, T>)",
     "                    if (false) {\n                        ((Parlia<B, T>)"),
    ("M4 Casper: o bloco entregue a si mesmo volta a entrar no C_B",
     NODE, "                    if (isLocalSelfDelivery(packet)) {\n                        ((CasperFFG<B, T>)",
     "                    if (false) {\n                        ((CasperFFG<B, T>)"),
    ("M5 Nakamoto: o bloco entregue a si mesmo volta a entrar no C_B",
     NODE, "                    if (isLocalSelfDelivery(packet)) {\n                        ((NakamotoConsensus<B, T>)",
     "                    if (false) {\n                        ((NakamotoConsensus<B, T>)"),
    ("M6 Parlia: autoentrega sai do C_B e nao e registrada em lugar nenhum",
     NODE, "((Parlia<B, T>) this.consensusAlgorithm).addSelfDeliveryBlockTraffic(block, packet.getSize());",
     "/* MUTACAO: byte descartado */"),
    ("M7 Casper: o evento nao carrega a autoentrega (sentinela no lugar da medida)",
     J + "consensus/algorithm/CasperFFG.java", "                        traffic,\n                        BlockFinalizationEvent.NOT_MEASURED,\n                        getSelfDeliveryTraffic(newlyFinalizedBlock),",
     "                        traffic,\n                        BlockFinalizationEvent.NOT_MEASURED,\n                        BlockFinalizationEvent.NOT_MEASURED,"),
    ("M8 sentinela vira zero (nao medido passa a parecer zero medido)",
     J + "simulator/event/BlockFinalizationEvent.java", "consensusMessageTraffic, NOT_MEASURED, NOT_MEASURED);",
     "consensusMessageTraffic, 0L, 0L);"),
    ("M9 DAGsper (nao afetado): o filtro de autoentrega e removido",
     NODE, "this.consensusAlgorithm instanceof DAGsper && !isLocalSelfDelivery(packet)) {",
     "this.consensusAlgorithm instanceof DAGsper) {"),
    ("M10 coluna nova do CSV por trial com outro nome",
     J + "log/EnhancedBlockFinalizationLogger.java", "cols[cols.length - 1] = \"Cb_autoentrega_B\";", "cols[cols.length - 1] = \"X\";"),
    ("M11 coluna nova do CSV por trial deixa de ser a ultima (colunas existentes mudam de posicao)",
     J + "log/EnhancedBlockFinalizationLogger.java", "cols = java.util.Arrays.copyOf(cols, cols.length + 1);\n            cols[cols.length - 1] = \"Cb_autoentrega_B\";",
     "cols = java.util.Arrays.copyOf(cols, cols.length + 1);\n            cols[cols.length - 1] = cols[0];\n            cols[0] = \"Cb_autoentrega_B\";"),
    ("M12 Nakamoto: o evento carrega a autoentrega do bloco ERRADO",
     J + "consensus/algorithm/NakamotoConsensus.java", "getSelfDeliveryTraffic(highestConfirmedBlock)", "getSelfDeliveryTraffic(currentMainChainHead)"),
    ("M13 aditivo: o MVPComparison grava 0 no lugar da sentinela -1",
     J + "example/MVPComparison.java", "return v < 0 ? \"-1\" : String.format(java.util.Locale.ROOT, \"%.3f\", v);",
     "return v < 0 ? \"0.000\" : String.format(java.util.Locale.ROOT, \"%.3f\", v);"),
    ("M14 DIVIDA do sweep: o teto vira >= (max-byz == universo passa a ser rejeitado como 'universo')",
     J + "example/ByzantineSweep.java", "if (maxByz > universeSize) {", "if (maxByz >= universeSize) {"),
    ("M15 DIVIDA do sweep: a mensagem deixa de nomear o universo",
     J + "example/ByzantineSweep.java", "\"contagem bizantina f=\" + maxByz + \" (--max-byz) maior que o universo de sorteio (\"",
      "\"contagem bizantina f=\" + maxByz + \" (--max-byz) maior que o de sorteio (\""),
    # ---- CSV basico do BlockFinalizationLogger (*-basic-log.csv) ----
    ("M16 basic-log: coluna ligada para o protocolo ERRADO (criterio negado)",
     BFL, "on = ((PeerBlockchainNode<?, ?>) o).getConsensusAlgorithm().separatesSelfDelivery();",
     "on = !((PeerBlockchainNode<?, ?>) o).getConsensusAlgorithm().separatesSelfDelivery();"),
    ("M17 basic-log: coluna ligada para TODOS os protocolos (HotStuff, Tendermint e DAGsper tambem)",
     BFL, "selfDeliveryColumn = on;", "selfDeliveryColumn = true;"),
    ("M18 basic-log: celula da coluna nova traz o trafego total no lugar da autoentrega",
     BFL, "row[row.length - 1] = Long.toString(finalizationEvent.getSelfDeliveryTraffic());",
     "row[row.length - 1] = Long.toString(finalizationEvent.getTrafficUntilFinalization());"),
    ("M19 basic-log: comentario '#' diz INCLUDING no lugar de EXCLUDING",
     BFL, "C_B_total EXCLUDING self-delivery", "C_B_total INCLUDING self-delivery"),
    ("M20 basic-log: sentinela -1 da celula vira 0 (nao medido parece zero medido)",
     BFL, "row[row.length - 1] = Long.toString(finalizationEvent.getSelfDeliveryTraffic());",
     "row[row.length - 1] = Long.toString(Math.max(0L, finalizationEvent.getSelfDeliveryTraffic()));"),
    ("M21 basic-log: coluna nova no INICIO do cabecalho (fora do fim)",
     BFL, "cols[cols.length - 1] = SELF_DELIVERY_COLUMN;",
     "cols[cols.length - 1] = cols[0];\n            cols[0] = SELF_DELIVERY_COLUMN;"),
    ("M22 basic-log: comentario '#' perde a SimulatorVersion",
     BFL, "base = base.concat(LinkLoadRecorder.SIMULATOR_VERSION);", "base = base.concat(\"?\");"),
    # ---- s.csv do ByzantineSweep ----
    ("M23 sweep: POW deixa de ter as colunas de autoentrega (tipo que separa ficou sem)",
     SWEEP, "\"POSA\".equals(type) || \"POW\".equals(type);", "\"POSA\".equals(type);"),
    ("M24 sweep: DAGSPER ganha as colunas de autoentrega (coluna ligada para protocolo errado)",
     SWEEP, "\"POSA\".equals(type) || \"POW\".equals(type);", "\"POSA\".equals(type) || \"POW\".equals(type) || \"DAGSPER\".equals(type);"),
    ("M25 sweep: celulas avg e std da autoentrega trocadas",
     SWEEP, ".append(selfFmt(self[0])).append(',').append(selfFmt(self[1]))",
     ".append(selfFmt(self[1])).append(',').append(selfFmt(self[0]))"),
    ("M26 sweep: autoentrega convertida para MB com divisor errado (KB)",
     SWEEP, "double selfMb = selfBytes / (1024.0 * 1024.0);", "double selfMb = selfBytes / 1024.0;"),
    ("M27 sweep: comentario '#' diz INCLUEM no lugar de EXCLUEM",
     SWEEP, "avgTraffic e stdTraffic EXCLUEM a autoentrega", "avgTraffic e stdTraffic INCLUEM a autoentrega"),
    ("M28 sweep: sentinela -1 da celula vira 0",
     SWEEP, "return mb < 0 ? \"-1\" : String.format(Locale.ROOT, \"%.6f\", mb);",
     "return mb < 0 ? \"0\" : String.format(Locale.ROOT, \"%.6f\", mb);"),
    ("M29 sweep: sentinela -1 das estatisticas (nenhum trial mediu) vira 0",
     SWEEP, "return new double[] {-1.0, -1.0};", "return new double[] {0.0, 0.0};"),
    ("M30 sweep: colunas de autoentrega no INICIO do cabecalho (fora do fim)",
     SWEEP, "new StringBuilder(BASE_HEADER).append(SELF_DELIVERY_COLUMNS).toString()",
     "new StringBuilder(SELF_DELIVERY_COLUMNS.substring(1)).append(',').append(BASE_HEADER).toString()"),
    ("M31 sweep: autoentrega medida igual a zero deixa de contar (>= 0 vira > 0)",
     SWEEP, "return comAutoentrega && selfBytes >= 0;", "return comAutoentrega && selfBytes > 0;"),
    ("M32 sweep: linha '#' declara a semente errada",
     SWEEP, "selfDeliveryComments(seedRaw)", "selfDeliveryComments(\"0\")"),
    ("M33 sweep: linha '#' perde a SimulatorVersion",
     SWEEP, ".append(jabs.log.LinkLoadRecorder.SIMULATOR_VERSION)", ".append(\"\")"),
    # ---- sobreviventes apontados pela revisao anterior ----
    ("L7 recordSelfDelivery: o total medido igual a zero deixa de contar (>= 0 vira > 0)",
     J + "metrics/SimulationMetrics.java", "if (totalBytes >= 0) {", "if (totalBytes > 0) {"),
    ("L7b recordSelfDelivery: a parte de consenso igual a zero deixa de contar (>= 0 vira > 0)",
     J + "metrics/SimulationMetrics.java", "if (consensusBytes >= 0) {", "if (consensusBytes > 0) {"),
    ("L8 EnhancedBlockFinalizationLogger: argumentos total e consenso de recordSelfDelivery trocados",
     J + "log/EnhancedBlockFinalizationLogger.java",
     "metrics.recordSelfDelivery(finalizationEvent.getSelfDeliveryTraffic(),\n                    finalizationEvent.getSelfDeliveryConsensusTraffic());",
     "metrics.recordSelfDelivery(finalizationEvent.getSelfDeliveryConsensusTraffic(),\n                    finalizationEvent.getSelfDeliveryTraffic());"),
]


def sha(p):
    """sha256 do conteudo com fim de linha normalizado (CRLF->LF): a copia pode ser regravada com CRLF no Windows."""
    return hashlib.sha256(p.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def rodar(mvn, copia):
    cmd = shlex.split(mvn)
    if cmd and cmd[0] == "mvn":
        cmd[0] = mvn_exe()
    r = subprocess.run(cmd + ["test", f"-Dtest={TESTES}"], capture_output=True, text=True, cwd=str(copia))
    return r.returncode, r.stdout + r.stderr


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--copia", default=str(TMP_OPENCODE / "d2-mut"))
    ap.add_argument("--mvn", default="mvn -o")
    ap.add_argument("--so", default="")
    a = ap.parse_args()
    copia = Path(a.copia).resolve()
    if not str(copia).startswith(str(TMP_OPENCODE) + os.sep):
        sys.exit(f"ERRO: a copia tem de ficar sob {TMP_OPENCODE}{os.sep} (a arvore de trabalho nao e tocada)")
    if copia.exists():
        shutil.rmtree(copia)
    copia.mkdir(parents=True)
    shutil.copytree(RAIZ / "src", copia / "src")
    shutil.copy(RAIZ / "pom.xml", copia / "pom.xml")
    escolhidos = {s.strip() for s in a.so.split(",") if s.strip()}
    mut = [m for m in MUTACOES if not escolhidos or m[0].split()[0] in escolhidos]
    originais = {}
    for nome, arq, velho, novo in mut:
        p = copia / arq
        originais.setdefault(arq, p.read_text(encoding="utf-8"))
        if originais[arq].count(velho) != 1:
            print(f"ERRO: ancora de '{nome}' encontrada {originais[arq].count(velho)} vez(es) em {arq} (esperado 1)")
            return 2
        if velho == novo:
            print(f"ERRO: mutacao '{nome}' nao muda nada")
            return 2
    shas = {arq: sha(copia / arq) for arq in originais}
    print("== baseline (copia do codigo atual) ==")
    rc0, out0 = rodar(a.mvn, copia)
    tr = [l for l in out0.splitlines() if "Tests run:" in l][-1:] or ["(sem linha Tests run)"]
    print(f"baseline: {'PASSOU' if rc0 == 0 else 'FALHOU'} {tr[0].strip()[:120]}")
    res = []
    try:
        for nome, arq, velho, novo in mut:
            print(f"== {nome} ==")
            (copia / arq).write_text(originais[arq].replace(velho, novo), encoding="utf-8")
            rc, out = rodar(a.mvn, copia)
            falhas = [l.strip()[:170] for l in out.splitlines() if "<<< FAILURE" in l or "<<< ERROR" in l or "COMPILATION ERROR" in l]
            print("  " + ("\n  ".join(falhas[:2]) if falhas else ("(rc=%d sem linha de falha)" % rc)))
            res.append((nome, rc != 0))
            (copia / arq).write_text(originais[arq], encoding="utf-8")
    finally:
        for arq, txt in originais.items():
            (copia / arq).write_text(txt, encoding="utf-8")
    ok_sha = all(sha(copia / arq) == shas[arq] for arq in originais)
    print("\n== resumo ==")
    for nome, det in res:
        print(f"{'DETECTADA    ' if det else 'NAO DETECTADA'}  {nome}")
    print(f"copia restaurada, sha256 identico: {ok_sha}")
    sobreviveram = [n for n, d in res if not d]
    print(f"{len(res) - len(sobreviveram)}/{len(res)} detectados; baseline {'ok' if rc0 == 0 else 'FALHOU'}")
    return 0 if (rc0 == 0 and not sobreviveram and ok_sha) else 1


if __name__ == "__main__":
    sys.exit(main())
