#!/usr/bin/env python3
"""Le a fixture canonica de lote e tx-size de HOTSTUFF e TENDERMINT (HS-CB-fase2, B2).

Fonte unica: src/test/resources/fixture/fixture-canonica.properties (a mesma que o teste Java le). FIXTURE DE TESTE:
nao e calibracao nem valor de pesquisa (a definir com o humano).
Uso: `from fixture_canonica import carregar, flags_lote_tx` ou `fixture_canonica.py` (imprime as quatro flags).
"""
import sys
from pathlib import Path

ARQUIVO = Path(__file__).resolve().parents[2] / "src" / "test" / "resources" / "fixture" / "fixture-canonica.properties"


def carregar(arquivo: Path = ARQUIVO) -> dict:
    d = {}
    for linha in arquivo.read_text(encoding="utf-8").splitlines():
        linha = linha.strip()
        if not linha or linha.startswith("#"):
            continue
        k, sep, v = linha.partition("=")
        if not sep:
            raise ValueError(f"linha invalida em {arquivo}: {linha!r}")
        d[k.strip()] = int(v.strip())
    for k in ("batch-size", "tx-size"):
        if k not in d:
            raise ValueError(f"{arquivo}: chave {k!r} ausente")
    return d


def flags_lote_tx(arquivo: Path = ARQUIVO) -> list:
    f = carregar(arquivo)
    return [f"--hs-batch-size={f['batch-size']}", f"--hs-tx-size={f['tx-size']}",
            f"--tm-batch-size={f['batch-size']}", f"--tm-tx-size={f['tx-size']}"]


if __name__ == "__main__":
    print(" ".join(flags_lote_tx()))
    sys.exit(0)
