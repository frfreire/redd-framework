#!/usr/bin/env python3
"""Portao V-A: ajusta modelos linear e quadratico ao trafego em funcao de n.

Somente biblioteca padrao. Uso:
  validar-complexidade.py dados.csv --esperado linear|quadratico \
      [--col-n n] [--col-y bytes_trafegados] [--protocolo NOME]

ATENCAO: so e valido sobre trafego MEDIDO. Declare a granularidade (por no ou
agregado de rede) no ADR antes de interpretar.
Saida: 0 consistente com o esperado; 1 inconsistente; 2 dados insuficientes.
"""
import argparse
import csv
import math
import sys
from statistics import mean


def resolver(A, b):
    n = len(b)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(M[r][c]))
        if abs(M[p][c]) < 1e-12:
            return None
        M[c], M[p] = M[p], M[c]
        for r in range(c + 1, n):
            f = M[r][c] / M[c][c]
            for k in range(c, n + 1):
                M[r][k] -= f * M[c][k]
    x = [0.0] * n
    for i in range(n - 1, -1, -1):
        x[i] = (M[i][n] - sum(M[i][j] * x[j] for j in range(i + 1, n))) / M[i][i]
    return x


def ajustar(xs, ys, grau):
    # normaliza x para estabilidade numerica
    xm = max(xs)
    z = [x / xm for x in xs]
    k = grau + 1
    A = [[sum(zi ** (i + j) for zi in z) for j in range(k)] for i in range(k)]
    b = [sum(y * zi ** i for zi, y in zip(z, ys)) for i in range(k)]
    c = resolver(A, b)
    if c is None:
        return None
    pred = [sum(c[i] * zi ** i for i in range(k)) for zi in z]
    ss_res = sum((y - p) ** 2 for y, p in zip(ys, pred))
    ym = mean(ys)
    ss_tot = sum((y - ym) ** 2 for y in ys)
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    m = len(ys)
    aic = m * math.log(max(ss_res, 1e-300) / m) + 2 * k
    return {"coef": c, "r2": r2, "aic": aic, "xm": xm}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--esperado", choices=["linear", "quadratico"], required=True)
    ap.add_argument("--col-n", default="n")
    ap.add_argument("--col-y", default="bytes_trafegados")
    ap.add_argument("--protocolo", default="(nao informado)")
    a = ap.parse_args()
    with open(a.csv, newline="", encoding="utf-8") as fh:
        linhas = [l for l in fh if not l.startswith("#")]
    rd = csv.DictReader(linhas, delimiter=";" if linhas and linhas[0].count(";") > linhas[0].count(",") else ",")
    for col in (a.col_n, a.col_y):
        if col not in (rd.fieldnames or []):
            print(f"ERRO: coluna '{col}' ausente. Colunas: {rd.fieldnames}")
            return 2
    pts = {}
    for r in rd:
        try:
            n, y = float(r[a.col_n]), float(r[a.col_y])
        except ValueError:
            continue
        if y < 0:
            continue  # -1 = nao medido
        pts.setdefault(n, []).append(y)
    ns = sorted(pts)
    if len(ns) < 4:
        print(f"DADOS INSUFICIENTES: {len(ns)} valores distintos de n (minimo 4, ideal >= 6).")
        return 2
    xs = [n for n in ns for _ in pts[n]]
    ys = [y for n in ns for y in pts[n]]
    lin, quad = ajustar(xs, ys, 1), ajustar(xs, ys, 2)
    if not lin or not quad:
        print("ERRO numerico no ajuste")
        return 2
    a2 = quad["coef"][2] / (quad["xm"] ** 2)
    print(f"protocolo={a.protocolo}  pontos={len(ys)}  valores_de_n={len(ns)}")
    print(f"linear:     R2={lin['r2']:.5f}  AIC={lin['aic']:.2f}")
    print(f"quadratico: R2={quad['r2']:.5f}  AIC={quad['aic']:.2f}  coef(n^2)={a2:.4g}")
    # quadratico so vence se melhora AIC de forma material e o coef de n^2 e positivo
    melhor = "quadratico" if (quad["aic"] < lin["aic"] - 4 and a2 > 0) else "linear"
    print(f"melhor ajuste: {melhor}  (esperado: {a.esperado})")
    if len(ns) < 6:
        print("AVISO: menos de 6 valores de n: distinguir linear de quadratico e fragil, sobretudo com n baixo.")
    if melhor == a.esperado:
        print("V-A CONSISTENTE")
        return 0
    print("V-A INCONSISTENTE: investigar medicao (formula fechada? hook de trafego ausente? granularidade?) antes de seguir.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
