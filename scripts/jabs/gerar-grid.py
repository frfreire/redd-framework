#!/usr/bin/env python3
"""Gera o grid completo de celulas experimentais a partir de uma especificacao.

Entrada (JSON):
{
  "protocolos": ["PBFT", "HOTSTUFF"],
  "n": [4, 7, 10, 16, 25],
  "carga": ["estacionaria", "sazonal"],
  "f": [0, 1],
  "sementes": 5,
  "semente_base": 12345,
  "duracao_s": 600
}
Saida: <dir>/cfg_<protocolo>_n<N>_carga<perfil>_f<byz>_s<semente>.json + manifesto.json
O manifesto lista todas as celulas esperadas: serve para detectar celula faltante.
Uso: gerar-grid.py fatores.json [--saida experimentos/celulas] [--simulador-commit HASH]
"""
import argparse
import datetime
import itertools
import json
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("fatores")
    ap.add_argument("--saida", default="experimentos/celulas")
    ap.add_argument("--simulador-commit", default="NAO_INFORMADO")
    a = ap.parse_args()
    f = json.loads(Path(a.fatores).read_text(encoding="utf-8"))
    for k in ("protocolos", "n", "sementes", "duracao_s"):
        if k not in f:
            sys.exit(f"ERRO: fator '{k}' ausente em {a.fatores}")
    cargas = f.get("carga", ["estacionaria"])
    fs = f.get("f", [0])
    base = f.get("semente_base", 12345)
    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    celulas = []
    for proto, n, carga, byz in itertools.product(f["protocolos"], f["n"], cargas, fs):
        if byz * 3 >= n and byz != 0:
            continue  # f >= n/3 nao e um cenario BFT valido; declare-o a parte se quiser
        for s in range(1, int(f["sementes"]) + 1):
            nome = f"cfg_{proto.lower()}_n{n}_carga{carga}_f{byz:02d}_s{s:02d}"
            cfg = {"id": nome, "protocolo": proto, "n": n, "carga": carga, "byzantinos": byz,
                   "semente": base + n * 1000 + s, "replicacao": s, "duracao_s": f["duracao_s"],
                   "simulador_commit": a.simulador_commit,
                   "gerado_em": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")}
            (saida / f"{nome}.json").write_text(json.dumps(cfg, indent=1), encoding="utf-8")
            celulas.append(nome)
    (saida / "manifesto.json").write_text(json.dumps({"n_celulas": len(celulas), "celulas": celulas,
                                                       "fatores": f}, indent=1), encoding="utf-8")
    print(f"{len(celulas)} celula(s) em {saida}/ (manifesto.json lista todas as esperadas)")


if __name__ == "__main__":
    main()
