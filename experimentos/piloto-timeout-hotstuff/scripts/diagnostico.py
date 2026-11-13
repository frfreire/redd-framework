#!/usr/bin/env python3
"""Diagnostico DESCRITIVO (sem alterar o simulador) do maior pico de D por n na verificacao m=3 e na medicao, e
conferencia dos estratos do ataque. Somente biblioteca padrao. Saida: diagnostico.json + stdout.

Pico: para a celula/altura de maior D de cada n (politica ROTATING, L=100), lista D, Fmin, Fmax (espalhamento entre
replicas na finalizacao da altura) e as D das alturas vizinhas, para ver se o pico e de UMA replica atrasada (Fmax-Fmin
grande) ou da altura inteira (Fmin tambem atrasado).
Estratos: confere, em cada celula com ataque, que 0 esta (LIDER_BIZANTINO) ou nao esta (LIDER_HONESTO) em byzantine_ids,
que |ids| = f e que as celulas com 0 blocos sao exatamente as STABLE com lider bizantino.
"""
import csv
import glob
import gzip
import json
from pathlib import Path

B = Path(__file__).resolve().parent.parent


def alt(d):
    with gzip.open(Path(d) / "obs" / "observador-por-altura.csv.gz", "rt") as f:
        return {int(r["height"]): r for r in csv.DictReader(f)}


def main():
    out = {"picos": {}, "estratos": {}}
    for n in (4, 16, 64, 128):
        melhor = None
        for base, pref in (("medicao", "med_rotating_L100"), ("verificacao", "ver_m3_rotating_L100")):
            for d in sorted(glob.glob(str(B / "dados-brutos" / base / f"{pref}_n{n}_*"))):
                a = alt(d)
                for h, r in a.items():
                    if h > 50 and (melhor is None or float(r["Rmax_s"]) > melhor[0]):
                        melhor = (float(r["Rmax_s"]), base, Path(d).name, h)
        D, base, nome, h = melhor
        a = alt(B / "dados-brutos" / base / nome)
        viz = {k: {"Rmax_s": round(float(a[k]["Rmax_s"]), 4), "Fmin_s": round(float(a[k]["Fmin_s"]), 4),
                   "espalhamento_Fmax_menos_Fmin_s": round(float(a[k]["Fmax_s"]) - float(a[k]["Fmin_s"]), 4),
                   "Rmax_node": int(a[k]["Rmax_node"]), "Rmin_s": round(float(a[k]["Rmin_s"]), 4)}
               for k in range(h - 2, h + 3) if k in a}
        out["picos"][n] = {"D_max": D, "conjunto": base, "celula": nome, "altura": h, "vizinhanca": viz}
        print(f"n={n:3d} D_max={D:.4f} ({base}/{nome} h={h})")
        for k, v in viz.items():
            print(f"    h={k}: {v}")
    ok = True
    contagem = {"LIDER_BIZANTINO": 0, "LIDER_HONESTO": 0}
    for d in sorted(glob.glob(str(B / "dados-brutos" / "ataque" / "atq_*"))):
        cfg = json.loads((Path(d) / "cfg.json").read_text())
        res = json.loads((Path(d) / "resultado.json").read_text())
        rs = json.loads((Path(d) / "obs" / "observador-resumo.json").read_text())
        ids = rs["byzantine_ids"]
        est = cfg["estrato"]
        coerente = (0 in ids) == (est == "LIDER_BIZANTINO") and len(ids) == cfg["byzantinos"] \
            and ids == cfg["conjunto_bizantino_sorteado"]
        zero_blocos = rs["altura_maxima_finalizada"] == 0
        esperado_zero = cfg["variante"]["leader_policy"] == "STABLE" and est == "LIDER_BIZANTINO"
        ok &= coerente and (zero_blocos == esperado_zero)
        contagem[est] += 1
        if not (coerente and zero_blocos == esperado_zero):
            print("INCONSISTENTE:", Path(d).name, ids, est, zero_blocos)
    out["estratos"] = {"celulas_conferidas": sum(contagem.values()), "por_estrato": contagem, "tudo_consistente": ok}
    print("estratos:", out["estratos"])
    (B / "diagnostico.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
