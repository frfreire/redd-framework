#!/usr/bin/env python3
"""Deriva, SEM simular nada, a lista exata de celulas esperadas da rodada K=40 a partir de config/fatores-k40-L100.json.

Mesma convencao de scripts/jabs/gerar-grid.py + piloto/scripts/gerar-celulas.py:
  semente = semente_base + n*1000 + replicacao ; id = <prefixo>_rotating_L<L>_n<n>_cargaestacionaria_f00_s<rr>.
Grava celulas-esperadas.json (ids, sementes, duracao, timeout). Na etapa 2 o manifesto produzido por gerar-celulas.py
tem de coincidir exatamente com este arquivo (conferir-manifesto).
Uso: especificar-celulas.py [gravar | conferir-manifesto <manifesto.json>]
"""
import json
import sys
from pathlib import Path

K40 = Path(__file__).resolve().parent.parent
CFG = K40 / "config" / "fatores-k40-L100.json"
SAIDA = K40 / "celulas-esperadas.json"


def derivar():
    f = json.loads(CFG.read_text(encoding="utf-8"))
    cel = []
    for pol in f["politicas"]:
        for L in f["L"]:
            for n in f["n"]:
                for r in range(1, int(f["sementes"]) + 1):
                    cel.append({"id": f"{f['prefixo']}_{pol.lower()}_L{L}_n{n}_cargaestacionaria_f00_s{r:02d}",
                                "n": n, "replicacao": r, "semente": f["semente_base"] + n * 1000 + r,
                                "duracao_s": f["duracao_s"][str(n)],
                                "view_timeout_s": f["view_timeout_por_n"][str(n)]})
    return {"n_celulas": len(cel), "por_n": {str(n): sum(1 for c in cel if c["n"] == n) for n in f["n"]},
            "semente_base": f["semente_base"], "celulas": cel}


def main():
    esp = derivar()
    if sys.argv[1:2] == ["gravar"] or len(sys.argv) == 1:
        SAIDA.write_text(json.dumps(esp, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"{esp['n_celulas']} celulas esperadas {esp['por_n']} -> {SAIDA.name}")
    elif sys.argv[1:2] == ["conferir-manifesto"]:
        man = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
        ok = man["celulas"] == [c["id"] for c in esp["celulas"]] and man["n_celulas"] == esp["n_celulas"]
        gravado = json.loads(SAIDA.read_text(encoding="utf-8"))
        ok = ok and gravado == esp
        print("manifesto == especificacao pre-registrada" if ok else "DIVERGE da especificacao pre-registrada")
        sys.exit(0 if ok else 1)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
