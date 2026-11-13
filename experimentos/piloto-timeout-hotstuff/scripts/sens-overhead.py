#!/usr/bin/env python3
"""Sensibilidade ao --hs-message-overhead (10 B de base x 100 B), com sementes PAREADAS (s01..s03, semente_base 12345),
ROTATING, L=100, n em {16, 128}. Somente biblioteca padrao. Saida: sens-overhead.json + stdout.
"""
import csv
import gzip
import json
import statistics as st
from pathlib import Path

B = Path(__file__).resolve().parent.parent


def D(d):
    with gzip.open(d / "obs" / "observador-por-altura.csv.gz", "rt") as f:
        return [float(r["Rmax_s"]) for r in csv.DictReader(f) if int(r["height"]) > 50]


def main():
    out = {}
    for n in (16, 128):
        a, b = [], []
        for s in (1, 2, 3):
            a += D(B / "dados-brutos" / "medicao" / f"med_rotating_L100_n{n}_cargaestacionaria_f00_s{s:02d}")
            b += D(B / "dados-brutos" / "verificacao" / f"sensovh100_rotating_L100_n{n}_cargaestacionaria_f00_s{s:02d}")
        out[n] = {"N_base": len(a), "N_ovh100": len(b), "media_base": st.fmean(a), "media_ovh100": st.fmean(b),
                  "variacao_media_pct": 100 * (st.fmean(b) / st.fmean(a) - 1), "max_base": max(a), "max_ovh100": max(b)}
        print(n, json.dumps(out[n]))
    (B / "sens-overhead.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
