#!/usr/bin/env python3
r"""Evidencia de BYZ-fora-da-faixa-hotstuff (ADR-0015, decisao 3 e D3(a)): nao regressao BYTE A BYTE do HOTSTUFF dentro
da faixa BFT e comportamento fora dela, comparando o build do HEAD (git archive, ver construir-base-head.py) com a
arvore de trabalho. Ao contrario de nao_regressao.py, NAO ignora linhas '#': a exigencia e "saida identica".

Todas as JVM rodam com -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 (obrigatorio para pesquisa). Flags --hs-* sao
FIXTURE DE TESTE (nao calibracao).

Uso:
  construir-base-head.py --commit HEAD --destino /tmp/opencode/byz-head      # uma vez
  nr_byz_fora_faixa.py --base /tmp/opencode/byz-head --atual . [--saida /tmp/opencode/byz-nr] [--n 7] [--duracao 30]

Casos (mesma semente, mesmos argumentos nos dois lados, exceto onde dito):
  A  honesto (sem --attack)                                         HEAD x atual       -> arquivos IDENTICOS
  B  EQUIVOCATION f = floor((n-1)/3) (no limite)                    HEAD x atual       -> arquivos IDENTICOS
  C  como B, atual com --allow-beyond-bft-threshold=true            atual x atual+flag -> arquivos IDENTICOS (a flag nao rotula o que esta dentro)
  D  EQUIVOCATION f = floor((n-1)/3)+1 sem a flag                   atual              -> falha ANTES de criar saida, mensagem cita n, f e limite
  E  como D com a flag                                              atual              -> roda; mvp-results.csv e mvp-results-cb.csv trazem '# fora_da_faixa=true n=.. limite_bft=..'
Saida: 0 = tudo conforme; 1 = divergencia; 2 = erro de execucao.
"""
import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

TMP_OPENCODE = Path(tempfile.gettempdir()) / "opencode"  # no Linux: /tmp/opencode

HS = ["--hs-view-timeout=10.0", "--hs-max-future-view-horizon=1000", "--hs-batch-size=20", "--hs-tx-size=512",
      "--hs-individual-signature-size=64", "--hs-aggregated-signature-size=96", "--hs-aggregated-share-size=96",
      "--hs-message-overhead=10", "--hs-signature-mode=INDIVIDUAL", "--hs-leader-policy=ROTATING",
      "--hs-voter-encoding=BITMAP"]
JVM = ["-XX:+UnlockExperimentalVMOptions", "-XX:hashCode=2"]


def classpath(raiz: Path) -> str:
    cp = raiz / "target" / "cp.txt"
    if not cp.exists():
        sys.exit(f"ERRO: {cp} ausente (rode mvn -o dependency:build-classpath -Dmdep.outputFile=target/cp.txt em {raiz})")
    return f"{raiz / 'target' / 'classes'}{os.pathsep}{cp.read_text(encoding='utf-8').strip()}"


def rodar(raiz: Path, saida: Path, n, dur, semente, extras):
    cmd = ["java", *JVM, "-cp", classpath(raiz), "jabs.example.MVPComparison", "--protocols=HOTSTUFF",
           f"--validators={n}", "--trials=1", f"--duration={dur}", f"--seed={semente}", f"--output={saida}", *HS, *extras]
    return subprocess.run(cmd, capture_output=True, text=True, cwd=str(raiz))


def arquivos(d: Path):
    return {p.relative_to(d).as_posix(): p.read_bytes() for p in sorted(d.rglob("*")) if p.is_file()}


def comparar(rotulo, a: Path, b: Path):
    fa, fb = arquivos(a), arquivos(b)
    if not fa:
        print(f"[{rotulo}] SEM_DADOS: {a} vazio")
        return False
    if fa.keys() != fb.keys():
        print(f"[{rotulo}] DIVERGENTE: arquivos diferentes: so em A={sorted(fa.keys() - fb.keys())} so em B={sorted(fb.keys() - fa.keys())}")
        return False
    ruins = [k for k in fa if fa[k] != fb[k]]
    if ruins:
        print(f"[{rotulo}] DIVERGENTE: bytes diferentes em {ruins}")
        return False
    print(f"[{rotulo}] IDENTICO: {len(fa)} arquivos ({', '.join(sorted(fa))}), {sum(len(v) for v in fa.values())} bytes")
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True, help="build do HEAD (construir-base-head.py)")
    ap.add_argument("--atual", default=".")
    ap.add_argument("--saida", default=str(TMP_OPENCODE / "byz-nr"))
    ap.add_argument("--n", type=int, default=7)
    ap.add_argument("--duracao", type=int, default=30)
    ap.add_argument("--semente", type=int, default=1)
    a = ap.parse_args()
    base, atual, saida = Path(a.base).resolve(), Path(a.atual).resolve(), Path(a.saida)
    if saida.exists() and any(saida.iterdir()):
        sys.exit(f"ERRO: saida {saida} ja existe e nao esta vazia (nao sobrescrevo)")
    n, lim = a.n, (a.n - 1) // 3
    atq = ["--attack=EQUIVOCATION"]
    ok = True

    def exige_rc0(r, rotulo):
        if r.returncode != 0:
            print(f"[{rotulo}] ERRO rc={r.returncode}\n{r.stdout[-600:]}\n{r.stderr[-600:]}")
            sys.exit(2)

    print(f"n={n} limite floor((n-1)/3)={lim} semente={a.semente} duracao={a.duracao}s JVM={' '.join(JVM)}")
    # A
    for nome, raiz in (("base", base), ("atual", atual)):
        exige_rc0(rodar(raiz, saida / f"A-{nome}", n, a.duracao, a.semente, []), f"A-{nome}")
    ok &= comparar("A honesto HEAD x atual", saida / "A-base", saida / "A-atual")
    # B e C
    f = [f"--byzantine-count={lim}"]
    for nome, raiz, ex in (("base", base, []), ("atual", atual, []), ("atual-flag", atual, ["--allow-beyond-bft-threshold=true"])):
        exige_rc0(rodar(raiz, saida / f"B-{nome}", n, a.duracao, a.semente, [*atq, *f, *ex]), f"B-{nome}")
    ok &= comparar(f"B f={lim} (no limite) HEAD x atual", saida / "B-base", saida / "B-atual")
    ok &= comparar(f"C f={lim} atual x atual+flag", saida / "B-atual", saida / "B-atual-flag")
    # D
    fora = [f"--byzantine-count={lim + 1}"]
    r = rodar(atual, saida / "D", n, a.duracao, a.semente, [*atq, *fora])
    msg = (r.stdout + r.stderr)
    cond = (r.returncode != 0 and not (saida / "D").exists() and f"n={n} f={lim + 1} limite floor((n-1)/3)={lim}" in msg)
    print(f"[D f={lim + 1} sem flag] {'OK' if cond else 'FALHOU'}: rc={r.returncode} saida_criada={(saida / 'D').exists()}")
    for linha in msg.splitlines():
        if "fora da faixa" in linha:
            print("      " + linha.strip()[:260])
    ok &= cond
    # E: n=7,f=3 roda (n=4,f=2 aborta por precondicao do ATAQUE, nao da flag: HotStuff.requireEquivocationSplit)
    exige_rc0(rodar(atual, saida / "E", n, a.duracao, a.semente, [*atq, *fora, "--allow-beyond-bft-threshold=true"]), "E")
    cabecalhos = []
    for csv in ("mvp-results.csv", "mvp-results-cb.csv"):
        txt = (saida / "E" / csv).read_text(encoding="utf-8")
        achou = f"# fora_da_faixa=true n={n} byzantine_count={lim + 1} limite_bft={lim}" in txt
        cabecalhos.append(achou)
        print(f"[E {csv}] {'rotulo presente' if achou else 'ROTULO AUSENTE'}")
    ok &= all(cabecalhos)
    print("RESULTADO:", "CONFORME" if ok else "DIVERGENTE")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
