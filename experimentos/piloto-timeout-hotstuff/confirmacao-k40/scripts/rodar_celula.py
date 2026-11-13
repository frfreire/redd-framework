#!/usr/bin/env python3
"""Executa UMA celula da rodada K=40: reaproveita piloto/scripts/rodar_celula.py (duas JVMs: MVPComparison oficial +
observador externo), redirecionando o build para /tmp/opencode/k40-hs e a saida para confirmacao-k40/.
JVM: -Xmx2g, SEM -XX:hashCode=2 (nao ha --load-record; ver PREREGISTRO.md, secao 5).
"""
import importlib.util
from pathlib import Path

AQUI = Path(__file__).resolve().parent
K40 = AQUI.parent
PIL = K40.parent
spec = importlib.util.spec_from_file_location("rodar_celula_piloto", PIL / "scripts" / "rodar_celula.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.DEST = Path("/tmp/opencode/k40-hs")
m.BASE = K40
m.main()
