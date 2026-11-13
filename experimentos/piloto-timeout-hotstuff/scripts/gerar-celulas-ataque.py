#!/usr/bin/env python3
"""Gera as celulas COM ATAQUE (EQUIVOCATION) do piloto, a partir de config/selecao-ataque.json.

Politicas: STABLE em todos os (n, f) selecionados (estratos LIDER_BIZANTINO e LIDER_HONESTO) e ROTATING so com f=1
(comparador). Mesmas replicacoes (= mesmo sorteio bizantino) nas duas politicas. Configs vem de gerar-grid.py
(uma chamada por (n, f), sementes = maior replicacao selecionada; so as selecionadas sao mantidas).
--hs-view-timeout = T da proposta (m adotado, L = 100) para o n. Duracao igual para todas as celulas.
Uso: gerar-celulas-ataque.py --selecao config/selecao-ataque.json --propostas propostas-timeout.json --m 3 \
        --duracao 600 --saida celulas/ataque
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[3]
GRID = RAIZ / "scripts" / "jabs" / "gerar-grid.py"
FIXA = {"max_future_view_horizon": 1000, "s_tx": 512, "sig_individual_B": 64, "sig_agregada_B": 96,
        "share_agregada_B": 96, "overhead_msg_B": 10, "signature_mode": "INDIVIDUAL", "voter_encoding": "BITMAP"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selecao", required=True)
    ap.add_argument("--propostas", required=True)
    ap.add_argument("--m", required=True)
    ap.add_argument("--duracao", type=float, required=True)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--simulador-commit", default="NAO_INFORMADO")
    ap.add_argument("--L", type=int, default=100)
    a = ap.parse_args()
    sel = json.loads(Path(a.selecao).read_text())
    prop = json.loads(Path(a.propostas).read_text())["propostas"]
    T = {p["n"]: p["propostas"][a.m]["T_s"] for p in prop if p["L"] == a.L}
    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    celulas = []
    for chave, s in sorted(sel["selecao"].items(), key=lambda kv: (kv[1]["n"], kv[1]["f"])):
        n, f = s["n"], s["f"]
        reps = {"LIDER_BIZANTINO": s["LIDER_BIZANTINO"], "LIDER_HONESTO": s["LIDER_HONESTO"]}
        rmax = max(max(v) for v in reps.values())
        with tempfile.TemporaryDirectory() as td:
            fat = Path(td) / "f.json"
            fat.write_text(json.dumps({"protocolos": ["HOTSTUFF"], "n": [n], "carga": ["estacionaria"], "f": [f],
                                       "sementes": rmax, "semente_base": sel["semente_base"],
                                       "duracao_s": a.duracao}))
            r = subprocess.run([sys.executable, str(GRID), str(fat), "--saida", str(Path(td) / "g"),
                                "--simulador-commit", a.simulador_commit], capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(r.stderr + r.stdout)
            man = json.loads((Path(td) / "g" / "manifesto.json").read_text())
            por_rep = {}
            for nome in man["celulas"]:
                cfg = json.loads((Path(td) / "g" / f"{nome}.json").read_text())
                por_rep[cfg["replicacao"]] = (nome, cfg)
        for pol in ("STABLE", "ROTATING"):
            if pol == "ROTATING" and f != 1:
                continue
            for estrato, lst in reps.items():
                for rep in lst:
                    nome, cfg = por_rep[rep]
                    cid = f"atq_{pol.lower()}_{estrato.lower()}_n{n}_f{f:02d}_s{rep:04d}"
                    v = dict(FIXA)
                    v.update({"view_timeout_s": T[n], "L": a.L, "leader_policy": pol, "attack": "EQUIVOCATION"})
                    cfg.update({"id": cid, "variante": v, "grupo": f"{pol}_{estrato}_n{n}_f{f}", "estrato": estrato,
                                "conjunto_bizantino_sorteado": s["conjuntos"][str(rep)],
                                "m_do_timeout": a.m, "fatores_origem": a.selecao})
                    (saida / f"{cid}.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False))
                    celulas.append(cid)
    (saida / "manifesto.json").write_text(json.dumps({"n_celulas": len(celulas), "celulas": celulas,
                                                       "fatores": {"selecao": a.selecao, "m": a.m, "T_por_n": T,
                                                                   "duracao_s": a.duracao}}, indent=1))
    print(f"{len(celulas)} celula(s) em {saida}/")


if __name__ == "__main__":
    main()
