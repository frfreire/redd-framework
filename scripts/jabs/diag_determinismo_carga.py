#!/usr/bin/env python3
r"""Diagnostico de determinismo sob carga (D3a-2a-2d HS-CB fase 2, B1). NAO e gate: e investigacao.

Roda N execucoes do MESMO comando do MVPComparison (mesma semente) com no maximo P JVMs simultaneas e compara
cada saida com a PRIMEIRA, no criterio do projeto (ignora colunas cujo nome contem 'hash' e linhas '#').
Pergunta: a saida de uma simulacao com semente fixa depende da carga da maquina?

Uso:
  diag_determinismo_carga.py --raiz /tmp/opencode/base-8c68084 --proto TENDERMINT --n 4 --dur 20 --semente 1 \
      --execucoes 30 --paralelo 12 --saida /tmp/opencode/b1/TM-p12 \
      --extra=--tm-propose-timeout=1.0 ... [--jvm-flag=-XX:+UnlockExperimentalVMOptions --jvm-flag=-XX:hashCode=2]
Saida (stdout): uma linha por execucao divergente (arquivo, primeira diferenca) e o resumo
  RESUMO proto n dur semente paralelo=P execucoes=N divergentes=D rc_erro=E
Codigo: 0 = nenhuma divergencia; 1 = divergencia(s); 2 = erro de execucao.
Autoteste sem JVM: --selftest
"""
import argparse
import csv
import os
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


def classpath(raiz: Path) -> str:
    cp = raiz / "target" / "cp.txt"
    return f"{raiz / 'target' / 'classes'}{os.pathsep}{cp.read_text(encoding='utf-8').strip()}"


def ler(path: Path):
    linhas = [l for l in path.read_text(encoding="utf-8", errors="replace").splitlines()
              if l.strip() and not l.startswith("#")]
    if not linhas:
        return [], []
    delim = ";" if linhas[0].count(";") > linhas[0].count(",") else ","
    rows = list(csv.reader(linhas, delimiter=delim))
    manter = [i for i, c in enumerate(rows[0]) if "hash" not in c.lower()]
    return [rows[0][i] for i in manter], [[r[i] for i in manter if i < len(r)] for r in rows[1:]]


def diferenca(a: Path, b: Path):
    """None se iguais no criterio do projeto; senao texto curto da primeira diferenca por arquivo."""
    fa = sorted(str(p.relative_to(a)) for p in a.rglob("*.csv"))
    fb = sorted(str(p.relative_to(b)) for p in b.rglob("*.csv"))
    if fa != fb:
        return f"arquivos diferem {fa} vs {fb}"
    notas = []
    for rel in fa:
        ca, ra = ler(a / rel)
        cb, rb = ler(b / rel)
        if ca != cb:
            notas.append(f"{rel}: cabecalho")
        elif len(ra) != len(rb):
            notas.append(f"{rel}: {len(ra)} vs {len(rb)} linhas")
        else:
            for i, (x, y) in enumerate(zip(ra, rb), 1):
                if x != y:
                    d = [(ca[j], x[j], y[j]) for j in range(min(len(x), len(y))) if x[j] != y[j]][:2]
                    notas.append(f"{rel} linha {i}: {d}")
                    break
    return "; ".join(notas) if notas else None


def corrompido(pasta: Path):
    """Linhas fisicas de dados cujo numero de colunas difere do cabecalho, somadas nos CSV por bloco (os que
    comecam com 'Time,'). Escrita concorrente no CsvWriter (AbstractCSVLogger.flushBuffer) entrelaca bytes e
    quebra isso; divergencia genuina da simulacao NAO altera a contagem de colunas."""
    ruins = 0
    for p in pasta.rglob("*.csv"):
        if p.name.startswith("mvp-results"):
            continue
        linhas = [l for l in p.read_text(encoding="utf-8", errors="replace").splitlines()
                  if l.strip() and not l.startswith("#")]
        if not linhas:
            continue
        k = linhas[0].count(",")
        ruins += sum(1 for l in linhas[1:] if l.count(",") != k)
    return ruins


def classe(a: Path, b: Path):
    """Classifica uma divergencia ja detectada: 'ORDEM' se, em todo CSV divergente, o MULTICONJUNTO de linhas
    (apos remover colunas hash e '#') e igual e so a ordem muda; 'CONTEUDO' se o multiconjunto difere."""
    from collections import Counter
    for p in sorted(a.rglob("*.csv")):
        ca, ra = ler(p)
        cb, rb = ler(b / p.relative_to(a))
        if ca == cb and ra != rb and Counter(map(tuple, ra)) != Counter(map(tuple, rb)):
            return "CONTEUDO"
    return "ORDEM"


def rodar(cmd, saida: Path, raiz: Path, tmax):
    saida.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=tmax, cwd=str(raiz))
        (saida.parent / (saida.name + ".log")).write_text(r.stdout + r.stderr, encoding="utf-8")
        return r.returncode, time.time() - t0
    except subprocess.TimeoutExpired:
        return "TIMEOUT", time.time() - t0


def selftest():
    with tempfile.TemporaryDirectory() as t:
        t = Path(t)
        for nome, corpo in (("a", "x,hash,y\n1,AAA,2\n"), ("b", "x,hash,y\n1,BBB,2\n"), ("c", "x,hash,y\n1,AAA,3\n")):
            (t / nome).mkdir()
            (t / nome / "r.csv").write_text("# comentario " + nome + "\n" + corpo, encoding="utf-8")
        assert diferenca(t / "a", t / "b") is None  # so hash e '#' diferem
        assert diferenca(t / "a", t / "c") is not None  # coluna real diferente
    print("SELFTEST OK")
    return 0


def comparar_dois(pa: Path, pb: Path):
    """Modo --comparar A B (dois CSV): imprime cabecalho, contagens e as primeiras linhas que divergem."""
    ca, ra = ler(pa)
    cb, rb = ler(pb)
    from collections import Counter
    print(f"multiconjunto de linhas igual (so a ordem muda): {Counter(map(tuple, ra)) == Counter(map(tuple, rb))}")
    print(f"cabecalho igual: {ca == cb}; linhas {len(ra)} vs {len(rb)}")
    k = next((i for i, (x, y) in enumerate(zip(ra, rb)) if x != y), None)
    if k is None:
        print("prefixo comum identico" if len(ra) != len(rb) else "IDENTICO")
        k = min(len(ra), len(rb))
    print(f"primeira divergencia na linha de dados {k + 1}")
    for i in range(max(0, k - 1), min(max(len(ra), len(rb)), k + 4)):
        print(f"  [{i + 1}] A={ra[i] if i < len(ra) else None}")
        print(f"  [{i + 1}] B={rb[i] if i < len(rb) else None}")
    return 0


def ordem_tempo(p: Path):
    """Modo --ordem CSV: onde a coluna Time (1a) DECRESCE nas linhas de dados e o tamanho dos blocos fora de ordem.
    Os eventos sao gerados em ordem crescente de tempo simulado; descida = linhas escritas fora da ordem de geracao."""
    c, r = ler(p)
    t = []
    for x in r:
        try:
            t.append(float(x[0]))
        except (ValueError, IndexError):
            t.append(None)
    desc = [i + 1 for i in range(1, len(t)) if t[i] is not None and t[i - 1] is not None and t[i] < t[i - 1]]
    nulos = sum(1 for v in t if v is None)
    print(f"{p}: {len(r)} linhas de dados; descidas de Time nas linhas {desc}; linhas com Time ilegivel: {nulos}")
    return 0


def main():
    if "--selftest" in sys.argv[1:]:
        return selftest()
    if len(sys.argv) == 3 and sys.argv[1] == "--ordem":
        return ordem_tempo(Path(sys.argv[2]))
    if len(sys.argv) == 4 and sys.argv[1] == "--comparar":
        return comparar_dois(Path(sys.argv[2]), Path(sys.argv[3]))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raiz", required=True)
    ap.add_argument("--proto", required=True)
    ap.add_argument("--n", required=True)
    ap.add_argument("--dur", required=True)
    ap.add_argument("--semente", type=int, default=1)
    ap.add_argument("--execucoes", type=int, default=30)
    ap.add_argument("--paralelo", type=int, default=1)
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--extra", action="append", default=[])
    ap.add_argument("--jvm-flag", dest="jvm_flag", action="append", default=[])
    ap.add_argument("--cp-prefixo", dest="cp_prefixo", default="", help="diretorio de classes que vai ANTES no classpath (variante de diagnostico)")
    ap.add_argument("--manter-saidas", action="store_true", help="nao apaga (esvazia) as saidas iguais a primeira")
    a = ap.parse_args()
    raiz, out = Path(a.raiz).resolve(), Path(a.saida)
    cp = classpath(raiz)
    if a.cp_prefixo:
        cp = f"{Path(a.cp_prefixo).resolve()}{os.pathsep}{cp}"
    out.mkdir(parents=True, exist_ok=True)

    def cmd(i):
        return ["java", *a.jvm_flag, "-cp", cp, "jabs.example.MVPComparison", f"--protocols={a.proto}",
                f"--validators={a.n}", "--trials=1", f"--duration={a.dur}", f"--seed={a.semente}",
                f"--output={out / f'e{i:03d}'}", *a.extra]

    print(f"{a.proto} n={a.n} dur={a.dur}s semente={a.semente} execucoes={a.execucoes} paralelo={a.paralelo} "
          f"jvm={' '.join(a.jvm_flag) or '(padrao)'}")
    with ThreadPoolExecutor(max_workers=a.paralelo) as ex:
        res = list(ex.map(lambda i: rodar(cmd(i), out / f"e{i:03d}", raiz, a.timeout), range(a.execucoes)))
    erros = [i for i, (rc, _) in enumerate(res) if rc != 0]
    ref = out / "e000"
    div = 0
    classes = {}
    for i in range(1, a.execucoes):
        if i in erros or 0 in erros:
            continue
        d = diferenca(ref, out / f"e{i:03d}")
        if d:
            div += 1
            cls = classe(ref, out / f"e{i:03d}")
            classes[cls] = classes.get(cls, 0) + 1
            print(f"  DIVERGENTE[{cls}] e{i:03d} x e000: {d}")
    linhas = sorted({sum(len(ler(p)[1]) for p in (out / f'e{i:03d}').rglob('*.csv')) for i in range(a.execucoes)
                     if i not in erros})
    seg = [t for _, t in res]
    print(f"  linhas de dados distintas entre execucoes: {linhas}; tempo medio por JVM {sum(seg)/len(seg):.1f}s")
    corr = [i for i in range(a.execucoes) if i not in erros and corrompido(out / f"e{i:03d}")]
    for i in corr:
        print(f"  CSV CORROMPIDO (colunas por linha != cabecalho) em e{i:03d}: {corrompido(out / f'e{i:03d}')} linhas")
    print(f"RESUMO {a.proto} n={a.n} dur={a.dur} semente={a.semente} paralelo={a.paralelo} "
          f"execucoes={a.execucoes} divergentes={div} {classes} corrompidos={len(corr)} rc_erro={len(erros)}")
    return 2 if erros else (1 if div else 0)


if __name__ == "__main__":
    sys.exit(main())
