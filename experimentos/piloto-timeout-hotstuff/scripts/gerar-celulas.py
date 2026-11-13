#!/usr/bin/env python3
"""Gera as celulas do piloto de timeout do HotStuff (por script, nunca a mao), usando scripts/jabs/gerar-grid.py.

Entrada (fatores.json):
{
  "n": [4,16,64,128], "politicas": ["ROTATING","STABLE"], "L": [10,100,500],
  "sementes": 8, "semente_base": 12345,
  "duracao_s": {"4": 3600, ...},            # por n (duracao de simulacao, s simulados)
  "variante_fixa": {... flags --hs-* nao fatoriais ...},
  "view_timeout_s": 60,                      # timeout passado na medicao (grande: nenhum dispara)
  "attack": null | "EQUIVOCATION",
  "byzantinos": {"4": 1, ...}                # so se attack != null (--byzantine-count por n)
  "prefixo": "med"                           # prefixo do id da celula
}
Saida: <saida>/<id>.json (um por celula) e <saida>/manifesto.json (todas as celulas esperadas).

Cada grupo (politica, L, n) chama gerar-grid.py (protocolo HOTSTUFF, 1 carga, f declarado) numa subpasta
temporaria; o cfg resultante e enriquecido com `variante` e o id e prefixado pelo grupo. A semente de cada celula
e a de gerar-grid.py: semente_base + n*1000 + replicacao (MVPComparison recebe --seed = semente_base + replicacao,
trial 0; ver rodar_celula.py). Mesma semente_base para todos os grupos (numeros aleatorios comuns).
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[3]
GRID = RAIZ / "scripts" / "jabs" / "gerar-grid.py"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("fatores")
    ap.add_argument("--saida", required=True)
    ap.add_argument("--simulador-commit", default="NAO_INFORMADO")
    a = ap.parse_args()
    f = json.loads(Path(a.fatores).read_text(encoding="utf-8"))
    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    celulas = []
    attack = f.get("attack")
    prefixo = f.get("prefixo", "med")
    for pol in f["politicas"]:
        for L in f["L"]:
            for n in f["n"]:
                byz = int(f["byzantinos"][str(n)]) if attack else 0
                with tempfile.TemporaryDirectory() as td:
                    fat = Path(td) / "fatores.json"
                    fat.write_text(json.dumps({
                        "protocolos": ["HOTSTUFF"], "n": [n], "carga": ["estacionaria"], "f": [byz],
                        "sementes": f["sementes"], "semente_base": f["semente_base"],
                        "duracao_s": f["duracao_s"][str(n)]}))
                    out = Path(td) / "g"
                    r = subprocess.run([sys.executable, str(GRID), str(fat), "--saida", str(out),
                                        "--simulador-commit", a.simulador_commit],
                                       capture_output=True, text=True)
                    if r.returncode != 0:
                        sys.exit(f"gerar-grid.py falhou: {r.stderr}{r.stdout}")
                    man = json.loads((out / "manifesto.json").read_text())
                    if man["n_celulas"] != f["sementes"]:
                        sys.exit(f"gerar-grid.py omitiu celulas (n={n}, f={byz}): {man['n_celulas']} != {f['sementes']}")
                    for nome in man["celulas"]:
                        cfg = json.loads((out / f"{nome}.json").read_text())
                        cid = f"{prefixo}_{pol.lower()}_L{L}_{nome[len('cfg_hotstuff_'):]}"
                        v = dict(f["variante_fixa"])
                        v.update({"view_timeout_s": f["view_timeout_s"], "L": L, "leader_policy": pol,
                                  "attack": attack})
                        if f.get("view_timeout_por_n"):
                            v["view_timeout_s"] = f["view_timeout_por_n"][str(n)]
                        cfg.update({"id": cid, "variante": v, "grupo": f"{pol}_L{L}_n{n}",
                                    "fatores_origem": str(a.fatores)})
                        (saida / f"{cid}.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False))
                        celulas.append(cid)
    (saida / "manifesto.json").write_text(json.dumps({"n_celulas": len(celulas), "celulas": celulas,
                                                       "fatores": f}, indent=1, ensure_ascii=False))
    print(f"{len(celulas)} celula(s) em {saida}/ (manifesto.json lista todas as esperadas)")


if __name__ == "__main__":
    main()
