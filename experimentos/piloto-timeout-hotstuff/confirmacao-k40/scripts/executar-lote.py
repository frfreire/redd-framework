#!/usr/bin/env python3
"""Executa o lote K=40 em paralelo (<= 8 JVMs; enxame.config.json): reaproveita piloto/scripts/executar-lote.py,
chamando o rodar_celula.py DESTA pasta (build em /tmp/opencode/k40-hs)."""
import importlib.util
from pathlib import Path

AQUI = Path(__file__).resolve().parent
PIL = AQUI.parent.parent
spec = importlib.util.spec_from_file_location("executar_lote_piloto", PIL / "scripts" / "executar-lote.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.AQUI = AQUI
m.main()
