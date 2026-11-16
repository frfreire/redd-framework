#!/usr/bin/env python3
"""EMENDA 1b: monta a tabela de artefatos VIGENTES (SHA-256) e a grava no lugar do marcador <!--TABELA--> do PREREGISTRO.md.
Roda uma unica vez (recusa se o marcador ja foi substituido). Depois: hash-preregistro.py gravar-emenda.
O proprio script entra na lista (o hash e o do arquivo em disco; ele nao muda depois de rodar).
"""
import hashlib
import sys
from pathlib import Path

VAL = Path(__file__).resolve().parent.parent
RAIZ = VAL.parents[2]
V = "experimentos/validacao/hotstuff"
ARTEFATOS = [
    f"{V}/PREREGISTRO.v1-original-2026-11-16.md",
    f"{V}/config/fatores-va.json", f"{V}/config/fatores-va-no.json", f"{V}/config/fatores-vb.json", f"{V}/config/fatores-vc.json",
    f"{V}/celulas-esperadas.json", f"{V}/checagem-sementes-emenda-1b.json", f"{V}/vb-celulas-bloqueadas.json",
    f"{V}/emenda-1b-previsoes.json", f"{V}/custo-estimado.json", f"{V}/FONTES-V-C.md", f"{V}/harness/ObservadorVal.java",
    *[f"{V}/scripts/{n}" for n in (
        "formula-analitica.py", "gerar-celulas-val.py", "checar-sementes-val.py", "preparar-val.py", "rodar_celula.py",
        "executar-lote.py", "analisar-val.py", "comparar-determinismo.py", "custo-estimado.py", "hash-preregistro.py",
        "dry-run-comandos.py", "sondagem-leitura.py", "emenda-1b-prever.py", "arquivar-original.py",
        "gerar-observador-val.py", "listar-vb-bloqueadas.py", "montar-tabela-emenda.py")],
    "experimentos/piloto-timeout-hotstuff/scripts/executar-lote.py",
    "experimentos/piloto-timeout-hotstuff/scripts/rodar_celula.py",
    "experimentos/piloto-timeout-hotstuff/harness/ObservadorHotStuff.java",
    "scripts/jabs/gerar-grid.py", "scripts/jabs/validar-complexidade.py",
]


def main():
    arq = VAL / "PREREGISTRO.md"
    txt = arq.read_text(encoding="utf-8")
    if txt.count("<!--TABELA-->") != 1:
        sys.exit("marcador <!--TABELA--> ausente ou duplicado (a tabela ja foi montada?)")
    linhas = ["| arquivo | SHA-256 |", "|---|---|"]
    for c in ARTEFATOS:
        p = RAIZ / c
        if not p.exists():
            sys.exit(f"artefato ausente: {c}")
        linhas.append(f"| `{c}` | `{hashlib.sha256(p.read_bytes()).hexdigest()}` |")
    arq.write_text(txt.replace("<!--TABELA-->", "\n".join(linhas) + "\n"), encoding="utf-8")
    print(len(ARTEFATOS), "artefatos na tabela vigente")


if __name__ == "__main__":
    main()
