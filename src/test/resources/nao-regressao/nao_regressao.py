#!/usr/bin/env python3
"""Evidencia de nao regressao (criterio 7) para protocolos NAO tocados pela T1 (ADR-0006).

Compara, com semente fixa, o codigo ATUAL contra uma BASELINE (copia do codigo original) e
mede a variabilidade da propria baseline (rodando-a duas vezes).

Para cada caso PROTOCOLO:N:DURACAO roda 4 execucoes independentes (processos JVM em paralelo,
cada uma com timeout proprio e saida isolada):
    base-a, base-b  (codigo original, mesma semente)
    atual-a, atual-b (codigo atual,   mesma semente)
e compara os CSV INTEIROS (so as colunas com 'hash' e as linhas em branco sao normalizadas; as linhas '#'
sao comparadas e DEVEM ser iguais, ver `ler`):
    base-a  x base-b   -> a baseline e deterministica? (se nao, divergencia e PRE-EXISTENTE)
    atual-a x atual-b  -> o atual e deterministico?
    base-a  x atual-a  -> regressao introduzida pela T1?

Por que o checar-determinismo.py estourou 120 s: ele roda com n pequeno (padrao --validadores 7).
Com n pequeno o setup da rede NAO TERMINA (nao e lentidao): AbstractBlockchainP2PConnections.
connectToNetwork repete ate achar numOutboundConnections vizinhos distintos e nao ha vizinhos
suficientes. Medido: Casper/MCO2 e Parlia/AMBIFY n=7 e Nakamoto/OFFSET n<=20 travam (rc=124 em 40 s);
OFFSET n=30 termina em 1 s. Parlia/Casper exigem n >= 10 (AGENTS.md); OFFSET exige n >= ~30.
Por isso os casos abaixo usam n e duracao (parametros --casos) que terminam em < 1 s.

Como reconstruir a baseline (nao ha commit): arvore isolada fora do repositorio com o src/main/java
atual, trocando os 4 arquivos tocados pela T1 pela versao ORIGINAL guardada em
src/test/resources/nao-regressao/baseline-original/*.txt (sha256 conferido contra /tmp/opencode/arq,
copia feita antes da T1). Compilar ambas com javac --release 17 (mvn clean compile no repo tambem
serve, mas aqui compilamos fora para nao tocar target/) e passar --base/--atual como raiz que
contem target/classes e target/cp.txt (classpath das dependencias).

Uso:
  nao_regressao.py --base /tmp/opencode/nr-build/base --atual /tmp/opencode/nr-build/atual \
      --casos MCO2:10:60,MCO2:50:600,AMBIFY:10:60,OFFSET:40:20000 --semente 1 --timeout 600
Saida: 0 = nenhuma regressao; 1 = divergencia baseline x atual; 2 = erro/timeout (INCONCLUSIVO).
A divergencia baseline-a x baseline-b (nao determinismo pre-existente) e IMPRESSA, mas nao muda o
codigo de saida: ela e a prova de que a diferenca nao vem da T1.
"""
import argparse
import csv
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def classpath(raiz: Path) -> str:
    cp = raiz / "target" / "cp.txt"
    if not cp.exists():
        r = subprocess.run(["mvn", "-q", "-o", "dependency:build-classpath", f"-Dmdep.outputFile={cp}"],
                           capture_output=True, text=True, cwd=str(raiz))
        if r.returncode != 0 or not cp.exists():
            sys.exit(f"ERRO: classpath de {raiz}: {r.stderr[-300:]}")
    return f"{raiz / 'target' / 'classes'}:{cp.read_text().strip()}"


def executar(raiz: Path, proto, n, dur, semente, saida: Path, tmax):
    saida.mkdir(parents=True, exist_ok=True)
    cmd = ["java", "-cp", classpath(raiz), "jabs.example.MVPComparison", f"--protocols={proto}",
           f"--validators={n}", "--trials=1", f"--duration={dur}", f"--seed={semente}", f"--output={saida}"]
    t0 = time.time()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=tmax, cwd=str(raiz))
        (saida.parent / (saida.name + ".log")).write_text(r.stdout + r.stderr)
        return r.returncode, time.time() - t0
    except subprocess.TimeoutExpired:
        return "TIMEOUT", time.time() - t0


def ler(path: Path):
    """Retorna (cabecalho, linhas, comentarios). As linhas '#' NAO sao descartadas: voltam em `comentarios`
    (texto bruto, na ordem) e `comparar` as exige IGUAIS entre os dois lados (rodada 4 da N1: antes elas eram
    ignoradas, o que escondeu que o atual escrevia 2 linhas '#' a mais no mvp-results.csv).
    Normalizacoes que RESTAM, e so estas: (1) linhas em branco sao puladas; (2) colunas cujo cabecalho contem
    'hash' sao removidas (identityHashCode do bloco, varia entre JVMs); (3) EXTRA_ATUAL, ver comparar."""
    todas = path.read_text(encoding="utf-8", errors="replace").splitlines()
    comentarios = [l for l in todas if l.strip() and l.startswith("#")]
    linhas = [l for l in todas if l.strip() and not l.startswith("#")]
    if not linhas:
        return [], [], comentarios
    delim = ";" if linhas[0].count(";") > linhas[0].count(",") else ","
    rows = list(csv.reader(linhas, delimiter=delim))
    manter = [i for i, c in enumerate(rows[0]) if "hash" not in c.lower()]
    return [rows[0][i] for i in manter], [[r[i] for i in manter if i < len(r)] for r in rows[1:]], comentarios


EXTRA_ATUAL = "TrafficConsensusUntilFinalization"  # coluna nova da T1 (ADR-0006); -1 = nao medido


def comparar(a: Path, b: Path, extra_ok=False):
    """Retorna (veredito, detalhe, linhas_comparadas). a = referencia, b = candidato.
    extra_ok: aceita em b a coluna EXTRA_ATUAL ausente em a, desde que valha -1 em todas as linhas
    (esquema ampliado, protocolo que nao mede a decomposicao); a coluna sai antes de comparar."""
    fa = sorted(p.relative_to(a) for p in a.rglob("*.csv"))
    fb = sorted(p.relative_to(b) for p in b.rglob("*.csv"))
    if not fa:
        return "SEM_DADOS", "nenhum CSV gerado", 0
    if fa != fb:
        return "DIVERGENTE", f"arquivos diferem {fa} vs {fb}", 0
    total, notas, extras = 0, [], 0
    for rel in fa:
        ca, ra, coma = ler(a / rel)
        cb, rb, comb = ler(b / rel)
        if coma != comb:
            notas.append(f"{rel}: linhas '#' diferem ({len(coma)} vs {len(comb)}): {coma[:2]} vs {comb[:2]}")
        if extra_ok and EXTRA_ATUAL in cb and EXTRA_ATUAL not in ca:
            k = cb.index(EXTRA_ATUAL)
            if any(len(r) > k and r[k] != "-1" for r in rb):
                notas.append(f"{rel}: {EXTRA_ATUAL} diferente de -1")
            extras += 1
            cb = cb[:k] + cb[k + 1:]
            rb = [r[:k] + r[k + 1:] for r in rb]
        total += len(ra)
        if ca != cb:
            notas.append(f"{rel}: cabecalho {ca} vs {cb}")
        elif len(ra) != len(rb):
            notas.append(f"{rel}: {len(ra)} vs {len(rb)} linhas")
        else:
            for i, (x, y) in enumerate(zip(ra, rb), 1):
                if x != y:
                    d = [(ca[j], x[j], y[j]) for j in range(min(len(x), len(y))) if x[j] != y[j]][:3]
                    notas.append(f"{rel} linha {i}: {d}")
                    break
    if total == 0:
        return "SEM_DADOS", "CSV sem linhas de dados", 0
    if extras and not notas:
        return "IDENTICO", f"(coluna nova {EXTRA_ATUAL}=-1 em todas as linhas, removida da comparacao)", total
    return ("IDENTICO" if not notas else "DIVERGENTE"), "; ".join(notas), total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--atual", required=True)
    ap.add_argument("--casos", default="MCO2:10:60,AMBIFY:10:60,OFFSET:10:60")
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--timeout", type=int, default=600, help="segundos por execucao")
    ap.add_argument("--saida", default="/tmp/opencode/nr")
    a = ap.parse_args()
    base, atual, out = Path(a.base).resolve(), Path(a.atual).resolve(), Path(a.saida)
    for r in (base, atual):
        if not (r / "target" / "classes").exists():
            sys.exit(f"ERRO: {r}/target/classes ausente (mvn -q -o clean compile)")
    jobs = []
    for caso in a.casos.split(","):
        proto, n, dur = caso.split(":")
        for lado, raiz in (("base", base), ("atual", atual)):
            for rep in ("a", "b"):
                jobs.append((proto, n, dur, lado, rep, raiz))
    print(f"semente={a.semente} timeout={a.timeout}s por execucao; {len(jobs)} execucoes em paralelo")
    with ThreadPoolExecutor(max_workers=len(jobs)) as ex:
        fut = {j: ex.submit(executar, j[5], j[0], j[1], j[2], a.semente,
                            out / f"{j[0]}-{j[1]}v-{j[2]}s-{j[3]}-{j[4]}", a.timeout) for j in jobs}
        res = {j: f.result() for j, f in fut.items()}
    pior = 0
    for caso in a.casos.split(","):
        proto, n, dur = caso.split(":")
        d = lambda lado, rep: out / f"{proto}-{n}v-{dur}s-{lado}-{rep}"
        st = {(l, r): res[(proto, n, dur, l, r, base if l == "base" else atual)] for l in ("base", "atual") for r in "ab"}
        print(f"\n== {proto} n={n} duracao={dur}s semente={a.semente}")
        print("   execucoes (rc, segundos): " + ", ".join(f"{l}-{r}={st[(l, r)][0]},{st[(l, r)][1]:.0f}s" for (l, r) in st))
        if any(v[0] != 0 for v in st.values()):
            print("   INCONCLUSIVO: execucao com erro/timeout")
            pior = max(pior, 2)
            continue
        for rot, x, y in (("baseline a x b (pre-existente?)", d("base", "a"), d("base", "b")),
                          ("atual    a x b                ", d("atual", "a"), d("atual", "b")),
                          ("baseline a x atual a (REGRESSAO?)", d("base", "a"), d("atual", "a"))):
            v, det, lin = comparar(x, y, extra_ok=rot.startswith("baseline a x atual"))
            print(f"   {rot}: {v} ({lin} linhas) {det}")
            if v == "SEM_DADOS":
                pior = max(pior, 2)
            elif v == "DIVERGENTE" and rot.startswith("baseline a x atual"):
                pior = max(pior, 1)
    return pior


if __name__ == "__main__":
    sys.exit(main())
