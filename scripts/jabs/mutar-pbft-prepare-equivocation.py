#!/usr/bin/env python3
"""Prova de sensibilidade a mutacao dos testes da tarefa PBFT-prepare-equivocation (ADR-0015 B-35 / Q28, lacuna L3).

Da raiz do repositorio:
    python3 scripts/jabs/mutar-pbft-prepare-equivocation.py [--mvn "mvn -o"]

Passos:
  1. baseline: roda os testes PBFT (PBFTPrepareEquivocationTest + PBFTPrepareEquivocationNaoRegressaoTest + PBFTEquivocationTest + PBFTQuorumTest +
     PBFTTrafficMeasurementTest) com o codigo atual; DEVE passar.
  2. mutantes (cada um aplicado sozinho sobre o fonte original; os mesmos testes DEVEM falhar em cada um):
       M1 hook do PREPARE desligado (isPrepareEquivocator sempre falso): o ataque volta a nao cobrir o PREPARE;
       M2 as duas metades recebem o bloco real (nao ha conflito);
       M3 metades trocadas (o bloco conflitante vai a primeira metade);
       M4 ataque ampliado: qualquer bizantino equivoca o PREPARE, seja qual for o ataque configurado;
       M5 o no nao-bizantino com config EQUIVOCATION tambem equivoca (checagem de bizantino removida);
       M6 pre-condicao de destinatarios honestos removida do PREPARE (requireEquivocationSplit nao chamado);
       M7 bloco conflitante nao reutilizado (um B novo por pacote: cada vizinho veria um mundo diferente);
       M8 bloco conflitante nao liberado ao finalizar o bloco real (vazamento);
       M9 bloco conflitante com hash/identidade igual ao real (alternativeFor devolve o proprio real);
        M10 (rodada 2, criterio 3) PREPARE do conflitante sem PRE_PREPARE volta a ser retido em pendingPrepares;
        M11 (rodada 2) o trafego do conflitante volta a abrir entrada em blockTraffic (guarda removida);
        M12 (rodada 2) o conflitante e um PBFTBlockWithTx comum (perde a marca que permite nao retelo).
        M7/M8/M9/M12 foram reescritos na rodada 4 (criterio 8: B e funcao pura de A, nada guardado):
        M7 B igual so por identidade (nao e valor); M8 REINTRODUZ a retencao A -> B sem poda (acumulacao); M9 B e o proprio
        real; M18 hashCode por identidade; M19 criador fora do equals (dois bizantinos coincidem); M20 retencao A -> B
        COM poda ao finalizar (a versao da rodada 3: a acumulacao de A nao finalizados continua).
     Imprime "DETECTADA" / "NAO DETECTADA" por mutante.
  3. restaura PBFT.java e PBFTConflictingBlock.java byte a byte (try/finally e em SIGINT/SIGTERM) e confere o sha256. Copia em
     /tmp/mutar-pbft-prepare.bak (se o processo for morto a forca: cp /tmp/mutar-pbft-prepare.bak <ALVO>).
Saida: 0 = baseline passou E todos os mutantes falharam; 1 = algum mutante passou ou baseline falhou; 2 = ancora nao
encontrada (script desatualizado em relacao ao fonte). Nao usa git.
"""
import argparse
import hashlib
import os
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
from pathlib import Path


def mvn_exe():
    """Executavel do Maven: mvn.cmd no Windows (subprocess sem shell nao resolve `mvn`), mvn fora dele."""
    if os.name == "nt":
        return shutil.which("mvn.cmd") or "mvn.cmd"
    return "mvn"


ALVO = Path("src/main/java/jabs/consensus/algorithm/PBFT.java")
CONF = Path("src/main/java/jabs/ledgerdata/pbft/PBFTConflictingBlock.java")
ARQS = [ALVO, CONF]
BACKUP = Path(tempfile.gettempdir()) / "mutar-pbft-prepare.bak"  # no Linux: /tmp/mutar-pbft-prepare.bak
BACKUP_CONF = Path(tempfile.gettempdir()) / "mutar-pbft-prepare-conf.bak"
TESTES = "PBFTPrepareEquivocationTest,PBFTPrepareEquivocationNaoRegressaoTest,PBFTEquivocationTest,PBFTQuorumTest,PBFTTrafficMeasurementTest"

MUTACOES = [
    ("M1 hook do PREPARE desligado",
     "                && \"EQUIVOCATION\".equals(getByzantineAttackType());\n    }\n\n    /**\n     * PREPARE equivocado",
     "                && \"EQUIVOCATION\".equals(getByzantineAttackType()) && false;\n    }\n\n    /**\n     * PREPARE equivocado"),
    ("M2 as duas metades recebem o bloco real",
     "i < half ? block : alternative);",
     "block);"),
    ("M3 metades trocadas",
     "i < half ? block : alternative);",
     "i < half ? alternative : block);"),
    ("M4 qualquer ataque equivoca o PREPARE (ampliado)",
     "        return isByzantineValidator(this.peerBlockchainNode.nodeID)\n                && \"EQUIVOCATION\".equals(getByzantineAttackType());",
     "        return isByzantineValidator(this.peerBlockchainNode.nodeID)\n                && getByzantineAttackType() != null;"),
    ("M5 checagem de bizantino removida",
     "        return isByzantineValidator(this.peerBlockchainNode.nodeID)\n                && \"EQUIVOCATION\".equals(getByzantineAttackType());",
     "        return \"EQUIVOCATION\".equals(getByzantineAttackType());"),
    ("M6 pre-condicao de destinatarios honestos removida do PREPARE",
     "        int half = requireEquivocationSplit(neighbors, \"PREPARE\");",
     "        int half = Math.max(1, neighbors.size() / 2);"),
    ("M7 bloco conflitante igual so por identidade (nao e funcao pura de A)",
     CONF,
     "        return conflictsWith == other.conflictsWith && getCreator() == other.getCreator();",
     "        return false;"),
    ("M8 retencao A -> B reintroduzida SEM poda (acumulacao de A nunca finalizados)",
     [(ALVO, "    private int currentViewNumber = 0;\n",
       "    private int currentViewNumber = 0;\n    private final HashMap<B, B> mutRetido = new HashMap<>();\n"),
      (ALVO, "        return (B) alternative;\n    }\n",
       "        mutRetido.put(real, (B) alternative);\n        return (B) alternative;\n    }\n")]),
    ("M9 bloco conflitante e o proprio real",
     ALVO,
     "        PBFTBlock alternative = new PBFTConflictingBlock((PBFTBlock) real, (PBFTNode) this.peerBlockchainNode);\n        if (alternative == real) {",
     "        PBFTBlock alternative = (PBFTBlock) real;\n        if (false) {"),
    ("M10 PREPARE do conflitante volta a ser retido",
     "                    } else if (block instanceof PBFTConflictingBlock) {\n",
     "                    } else if (block instanceof PBFTConflictingBlock && false) {\n"),
    ("M11 trafego do conflitante volta a abrir entrada por bloco (retencao eterna)",
     "        if (block instanceof PBFTConflictingBlock) {\n            conflictingTotalBytes += bytes;\n            return;\n        }",
     "        if (block instanceof PBFTConflictingBlock) {\n            conflictingTotalBytes += bytes;\n            blockTraffic.put(block, bytes);\n            return;\n        }"),
    ("M12 conflitante sem a marca PBFTConflictingBlock",
     "        PBFTBlock alternative = new PBFTConflictingBlock((PBFTBlock) real, (PBFTNode) this.peerBlockchainNode);",
     "        PBFTBlock alternative = new PBFTBlockWithTx(real.getSize(), real.getHeight(), real.getCreationTime(),\n                (PBFTNode) this.peerBlockchainNode, (PBFTBlock) real.getParents().get(0), null);"),
    ("M13 (rodada 3, criterio 1) bytes do conflitante descartados de novo (C_B_total)",
     "            conflictingTotalBytes += bytes;\n",
     "            // MUTACAO: bytes descartados\n"),
    ("M14 (rodada 3) bytes do conflitante descartados de novo (C_B_consenso)",
     "            conflictingConsensusBytes += bytes;\n",
     "            // MUTACAO: bytes descartados\n"),
    ("M15 (rodada 3) bytes posteriores a finalizacao descartados (livro nao fecha)",
     "            afterFinalizationTotalBytes += bytes;\n",
     "            // MUTACAO: bytes descartados\n"),
    ("M16 (rodada 3) bytes do conflitante contados em dobro",
     "            conflictingTotalBytes += bytes;\n",
     "            conflictingTotalBytes += 2 * bytes;\n"),
    ("M17 (rodada 3) bytes do conflitante atribuidos ao C_B do bloco real (palpite): entrada por bloco",
     "            conflictingConsensusBytes += bytes;\n",
     "            conflictingConsensusBytes += bytes;\n            consensusTraffic.put(block, bytes);\n"),
    ("M18 (rodada 4) hashCode do conflitante por identidade (B de um mesmo A com hash diferente)",
     CONF,
     "        int h = 17;\n",
     "        if (true) return System.identityHashCode(this);\n        int h = 17;\n"),
    ("M19 (rodada 4) criador fora do equals (dois bizantinos fabricam o mesmo B)",
     CONF,
     "conflictsWith == other.conflictsWith && getCreator() == other.getCreator();",
     "conflictsWith == other.conflictsWith;"),
    ("M20 (rodada 4) retencao A -> B COM poda ao finalizar A (versao da rodada 3): A nao finalizados acumulam",
     [(ALVO, "    private int currentViewNumber = 0;\n",
       "    private int currentViewNumber = 0;\n    private final HashMap<B, B> mutRetido = new HashMap<>();\n"),
      (ALVO, "        return (B) alternative;\n    }\n",
       "        mutRetido.put(real, (B) alternative);\n        return (B) alternative;\n    }\n"),
      (ALVO, "                            pendingCommits.remove(block);\n",
       "                            pendingCommits.remove(block);\n                            mutRetido.remove(block);\n")]),
]


def rodar(mvn):
    cmd = shlex.split(mvn)
    if cmd and cmd[0] == "mvn":
        cmd[0] = mvn_exe()
    r = subprocess.run(cmd + ["test", f"-Dtest={TESTES}"], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def resumo(saida):
    chaves = ("Tests run:", "BUILD", "COMPILATION", "ERROR] /")
    return "\n".join(l[:200] for l in saida.splitlines() if any(k in l for k in chaves))[-900:]


def normaliza(m):
    """Cada mutante vira (nome, [(arquivo, ancora, substituto), ...]); formas aceitas na lista MUTACOES:
    (nome, velho, novo) [sobre PBFT.java], (nome, arquivo, velho, novo) e (nome, [(arquivo, velho, novo), ...])."""
    if len(m) == 2:
        return m[0], list(m[1])
    if len(m) == 3:
        return m[0], [(ALVO, m[1], m[2])]
    return m[0], [(m[1], m[2], m[3])]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mvn", default="mvn -o")
    a = ap.parse_args()
    for f in ARQS:
        if not f.exists():
            print(f"ERRO: {f} nao existe; rode da raiz do repositorio")
            return 2
    originais = {f: f.read_bytes() for f in ARQS}
    shas = {f: hashlib.sha256(b).hexdigest() for f, b in originais.items()}
    textos = {f: b.decode("utf-8").replace("\r\n", "\n") for f, b in originais.items()}  # ancoras usam LF; arvore pode estar em CRLF
    mutantes = [normaliza(m) for m in MUTACOES]
    for nome, trocas in mutantes:
        for arq, velho, novo in trocas:
            if textos[arq].count(velho) != 1:
                print(f"ERRO: ancora de '{nome}' em {arq.name} encontrada {textos[arq].count(velho)} vez(es) (esperado 1); atualize o script")
                return 2
            if velho == novo:
                print(f"ERRO: mutacao '{nome}' nao muda nada")
                return 2
    BACKUP.write_bytes(originais[ALVO])
    BACKUP_CONF.write_bytes(originais[CONF])

    def restaurar(*_):
        for f in ARQS:
            f.write_bytes(originais[f])

    def ao_sinal(signum, _frame):
        restaurar()
        sys.exit(130)

    signal.signal(signal.SIGINT, ao_sinal)
    signal.signal(signal.SIGTERM, ao_sinal)
    resultados = []
    rc0 = 1
    try:
        print("== baseline (codigo atual) ==")
        rc0, out0 = rodar(a.mvn)
        print(resumo(out0))
        print(f"baseline: {'PASSOU' if rc0 == 0 else 'FALHOU'} (rc={rc0})")
        for i, (nome, trocas) in enumerate(mutantes, 1):
            print(f"== mutante {i}/{len(mutantes)}: {nome} ==")
            novos = dict(textos)
            for arq, velho, novo in trocas:
                novos[arq] = novos[arq].replace(velho, novo)
            for f in ARQS:
                f.write_text(novos[f], encoding="utf-8")
            rc, out = rodar(a.mvn)
            falhas = [l for l in out.splitlines() if "<<< FAILURE" in l or "<<< ERROR" in l]
            print(resumo(out) if rc == 0 else "\n".join(l[:160] for l in falhas[:4]))
            resultados.append((nome, rc != 0))
            restaurar()
    finally:
        restaurar()
    ok = all(hashlib.sha256(f.read_bytes()).hexdigest() == shas[f] for f in ARQS)
    print(f"arquivos restaurados, sha256 identico ao inicial: {ok}")
    if not ok:
        print(f"ERRO: restauracao divergente; copias em {BACKUP} e {BACKUP_CONF}")
        return 1
    print("== resultado por mutante ==")
    for nome, det in resultados:
        print(f"  {'DETECTADA     ' if det else 'NAO DETECTADA'}  {nome}")
    if rc0 != 0:
        print("INVALIDO: o baseline ja falha")
        return 1
    if all(d for _, d in resultados):
        print("TODOS DETECTADOS: os testes sao sensiveis a mutacao")
        return 0
    print("TESTE INSENSIVEL: ao menos um mutante passou")
    return 1


if __name__ == "__main__":
    sys.exit(main())
