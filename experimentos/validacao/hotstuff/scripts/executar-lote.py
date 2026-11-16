#!/usr/bin/env python3
"""Executa o lote V-A/V-C em paralelo (<= 8 JVMs em processos; enxame.config.json maxCelulasDeSimulacaoParalelas = 8):
reaproveita piloto/scripts/executar-lote.py, chamando o rodar_celula.py DESTA pasta (build em /tmp/opencode/val-hs).
Uso: executar-lote.py <dir_celulas> --saida <dir_brutos> [--paralelo 8] [--pular-existentes]
Recusa o bloco `vb` (aguarda BYZ-fora-da-faixa-hotstuff).
"""
import importlib.util
import json
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
PIL = AQUI.parents[2] / "piloto-timeout-hotstuff"
man = json.loads((Path(sys.argv[1]) / "manifesto.json").read_text())
if man["fatores"].get("bloco") == "vb" or "vb_" in "".join(man["celulas"][:1]):
    sys.exit("ERRO: V-B NAO roda nesta etapa (aguarda BYZ-fora-da-faixa-hotstuff)")
spec = importlib.util.spec_from_file_location("executar_lote_piloto", PIL / "scripts" / "executar-lote.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.AQUI = AQUI
m.main()
