#!/usr/bin/env python3
"""Suplemento (pos-hoc, rotulado como tal; NAO altera o aceite congelado): p99,99 de D empilhando medicao (semente_base
12345) + verificacao (22345), mesma politica e mesmo L=100, 20 sementes disjuntas. Somente biblioteca padrao.

Motivo: com N~21,6 mil por grupo o IC95 por estatistica de ordem do quantil 0,9999 nao tem limite superior (a amostra
precisa de N >= ln(0,025)/ln(0,9999) = 36.889 para que o MAXIMO limite o quantil por cima a 97,5%). Empilhando as duas
campanhas (N~43 mil) o limite existe. As duas politicas NAO sao empilhadas entre si (mesmas sementes => nao independentes).
Saida: suplemento-pooled.json + stdout.
"""
import csv
import glob
import gzip
import importlib.util
import json
import math
from pathlib import Path

B = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("an", B / "scripts" / "analisar.py")
an = importlib.util.module_from_spec(spec)
spec.loader.exec_module(an)


def D(d):
    with gzip.open(Path(d) / "obs" / "observador-por-altura.csv.gz", "rt") as f:
        return [float(r["Rmax_s"]) for r in csv.DictReader(f) if int(r["height"]) > 50]


def main():
    N_min = math.ceil(math.log(0.025) / math.log(0.9999))
    out = {"N_minimo_para_limite_superior_do_IC95_p99_99": N_min, "grupos": {}}
    print("N minimo p/ limite superior:", N_min)
    for pol in ("rotating", "stable"):
        for n in (4, 16, 64, 128):
            xs = []
            for base, pref in (("medicao", f"med_{pol}_L100"), ("verificacao", f"ver_m3_{pol}_L100")):
                for d in sorted(glob.glob(str(B / "dados-brutos" / base / f"{pref}_n{n}_*"))):
                    xs += D(d)
            s = sorted(xs)
            q, rk = an.nearest_rank(s, 0.9999)
            inf, sup, ri, rs_ = an.ic_ordem(s, 0.9999)
            lo, hi = an.boot_quantil(xs, 0.9999)
            g = {"N": len(s), "p99_99": q, "posto": rk, "max": s[-1], "IC95_ordem": [inf, sup],
                 "IC95_bootstrap": [lo, hi], "max_sobre_p99_99": s[-1] / q,
                 "m_minimo_A3_com_este_p99_99": 2 * s[-1] / q}
            out["grupos"][f"{pol}_n{n}"] = g
            print(f"{pol[:3]} n={n:3d} N={len(s)} p99,99={q:.4f} (IC ordem {inf:.4f}; {'inf' if sup is None else f'{sup:.4f}'}) "
                  f"boot ({lo:.4f}; {hi:.4f}) max={s[-1]:.4f} max/p99,99={s[-1]/q:.2f} m_min(A3)={2*s[-1]/q:.2f}")
    (B / "suplemento-pooled.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
