#!/usr/bin/env python3
"""Executa um lote da confirmacao em paralelo (<= 8 JVMs; enxame.config.json): reaproveita scripts/executar-lote.py
do piloto, mas chamando o rodar_celula.py DESTA pasta (build em /tmp/opencode/confirmacao-hs)."""
import importlib.util
from pathlib import Path

AQUI = Path(__file__).resolve().parent
PIL = AQUI.parent.parent
spec = importlib.util.spec_from_file_location("executar_lote_piloto", PIL / "scripts" / "executar-lote.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.AQUI = AQUI
m.main()
