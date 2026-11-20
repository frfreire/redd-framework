#!/usr/bin/env python3
"""Executa o lote do V-ABC do PBFT puro em paralelo (<= 8 JVMs; enxame.config.json maxCelulasDeSimulacaoParalelas = 8), reaproveitando
experimentos/piloto-timeout-hotstuff/scripts/executar-lote.py com o rodar_celula.py DESTA pasta (build em /tmp/opencode/val-pbft).
RASCUNHO: so roda depois do pino, do commit e do hash do pre-registro.
Uso: executar-lote-pbft.py <dir_celulas> --saida <dir_brutos> [--paralelo 8] [--pular-existentes]
"""
import importlib.util
import json
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
PIL = AQUI.parents[2] / "piloto-timeout-hotstuff"
man = json.loads((Path(sys.argv[1]) / "manifesto.json").read_text())
bloco = man["fatores"].get("bloco")
if bloco != "va":
    sys.exit("ERRO: manifesto sem bloco va (gerar-celulas-pbft.py)")
spec = importlib.util.spec_from_file_location("executar_lote_piloto", PIL / "scripts" / "executar-lote.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.AQUI = AQUI
m.main()
