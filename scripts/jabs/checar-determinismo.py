#!/usr/bin/env python3
"""Confere determinismo por semente: roda o mesmo cenario 2 vezes e compara os CSV.

Colunas cujo nome contem 'hash' (hashCode de objeto varia por execucao) sao
ignoradas, assim como linhas de comentario (#).

Uso:
  checar-determinismo.py --jabs . --protocolo ARQ --validadores 7 \
      [--duracao 60] [--semente 1] [--trials 1]
Saida: 0 identico; 1 divergente; 2 erro de execucao.
"""
import argparse
import csv
import subprocess
import sys
import tempfile
from pathlib import Path


def classpath(jabs: Path) -> str:
    """target/classes + dependencias. `-cp target/classes` sozinho da ClassNotFoundException
    (commons-math3, fastcsv): o run-mvp.sh do JABS tem esse defeito."""
    saida = Path(tempfile.gettempdir()) / f"jabs-cp-{abs(hash(str(jabs)))}.txt"
    r = subprocess.run(["mvn", "-q", "-o", "dependency:build-classpath", f"-Dmdep.outputFile={saida}"],
                       capture_output=True, text=True, cwd=str(jabs))
    if r.returncode != 0 or not saida.exists():
        print("ERRO: nao consegui montar o classpath por Maven (offline).", r.stderr[-400:])
        sys.exit(2)
    return f"{jabs / 'target' / 'classes'}:{saida.read_text().strip()}"


def rodar(jabs: Path, proto, n, dur, semente, trials, saida: Path):
    cmd = ["java", "-cp", classpath(jabs), "jabs.example.MVPComparison",
           f"--protocols={proto}", f"--validators={n}", f"--trials={trials}",
           f"--duration={dur}", f"--seed={semente}", f"--output={saida}"]
    r = subprocess.run(cmd, capture_output=True, text=True, cwd=str(jabs))
    if r.returncode != 0:
        print("ERRO ao executar:", " ".join(cmd), "\n", r.stdout[-800:], r.stderr[-800:])
        sys.exit(2)
    return r


def ler_csv(path: Path):
    linhas = [l for l in path.read_text(encoding="utf-8", errors="replace").splitlines() if l.strip() and not l.startswith("#")]
    if not linhas:
        return [], []
    delim = ";" if linhas[0].count(";") > linhas[0].count(",") else ","
    rows = list(csv.reader(linhas, delimiter=delim))
    cab = rows[0]
    manter = [i for i, c in enumerate(cab) if "hash" not in c.lower()]
    return [cab[i] for i in manter], [[r[i] for i in manter if i < len(r)] for r in rows[1:]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jabs", required=True)
    ap.add_argument("--protocolo", required=True)
    ap.add_argument("--validadores", type=int, default=7)
    ap.add_argument("--duracao", type=int, default=60)
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--trials", type=int, default=1)
    a = ap.parse_args()
    jabs = Path(a.jabs).resolve()
    if not (jabs / "target" / "classes").exists():
        print(f"ERRO: {jabs}/target/classes ausente. Rode: (cd {jabs} && mvn -q clean compile)")
        return 2
    with tempfile.TemporaryDirectory() as tmp:
        saidas = []
        for k in (1, 2):
            d = Path(tmp) / f"r{k}"
            d.mkdir()
            rodar(jabs, a.protocolo, a.validadores, a.duracao, a.semente, a.trials, d)
            saidas.append(d)
        arqs1 = sorted(p.relative_to(saidas[0]) for p in saidas[0].rglob("*.csv"))
        arqs2 = sorted(p.relative_to(saidas[1]) for p in saidas[1].rglob("*.csv"))
        if not arqs1:
            print("ERRO: nenhum CSV gerado: nada foi comparado.")
            return 2
        if arqs1 != arqs2:
            print(f"DIVERGENTE: conjuntos de arquivos diferem: {arqs1} vs {arqs2}")
            return 1
        ok = True
        total = 0
        for rel in arqs1:
            c1, r1 = ler_csv(saidas[0] / rel)
            c2, r2 = ler_csv(saidas[1] / rel)
            total += len(r1)
            if c1 != c2:
                print(f"DIVERGENTE {rel}: cabecalhos diferem")
                ok = False
            elif len(r1) != len(r2):
                print(f"DIVERGENTE {rel}: {len(r1)} vs {len(r2)} linhas")
                ok = False
            else:
                for i, (x, y) in enumerate(zip(r1, r2), 1):
                    if x != y:
                        dif = [(c1[j], x[j], y[j]) for j in range(min(len(x), len(y))) if x[j] != y[j]][:3]
                        print(f"DIVERGENTE {rel} linha {i}: {dif}")
                        ok = False
                        break
        if ok and total == 0:
            print("INCONCLUSIVO: CSV sem linhas de dados (simulacao curta demais ou falha silenciosa do trial).")
            return 2
        print(("IDENTICO" if ok else "DIVERGENTE") + f": {len(arqs1)} arquivo(s), {total} linha(s) comparadas (colunas *hash* ignoradas)")
        return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
