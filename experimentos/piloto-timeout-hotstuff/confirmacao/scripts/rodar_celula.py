#!/usr/bin/env python3
"""Executa UMA celula da confirmacao: reaproveita scripts/rodar_celula.py do piloto (duas JVMs: MVPComparison oficial
+ observador externo), redirecionando o build para /tmp/opencode/confirmacao-hs e a saida para confirmacao/.
JVM: -Xmx2g, SEM -XX:hashCode=2 (o observador e o MVPComparison nao usam --load-record; ver CRITERIOS-congelados.md).
"""
import importlib.util
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
CONF = AQUI.parent
PIL = CONF.parent
spec = importlib.util.spec_from_file_location("rodar_celula_piloto", PIL / "scripts" / "rodar_celula.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.DEST = Path("/tmp/opencode/confirmacao-hs")
m.BASE = CONF
m.main()
