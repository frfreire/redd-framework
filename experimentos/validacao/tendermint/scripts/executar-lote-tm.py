#!/usr/bin/env python3
"""Executa o lote do V-ABC do Tendermint em paralelo (<= 8 JVMs; enxame.config.json maxCelulasDeSimulacaoParalelas = 8), reaproveitando
experimentos/piloto-timeout-hotstuff/scripts/executar-lote.py com o rodar_celula.py DESTA pasta (build em /tmp/opencode/val-tm).
RASCUNHO: so roda depois do pino e do commit do pre-registro.
Uso: executar-lote-tm.py <dir_celulas> --saida <dir_brutos> [--paralelo 8] [--pular-existentes]
Em V-B passa SEMPRE --rc-mvp-registrado (nao ha JVM do MVPComparison). O codigo de saida 3 do lote e ESPERADO em V-B quando ha celula que aborta
pela regra do ataque (rc_observador != 0): quem classifica e analisar-vb-tm.py. Nenhuma celula e reexecutada nem substituida por outra semente.
"""
import importlib.util
import json
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
PIL = AQUI.parents[2] / "piloto-timeout-hotstuff"
man = json.loads((Path(sys.argv[1]) / "manifesto.json").read_text())
bloco = man["fatores"].get("bloco")
if bloco not in ("va", "vc", "vb"):
    sys.exit("ERRO: manifesto sem bloco va/vc/vb (gerar-celulas-tm.py)")
if bloco == "vb" and "--rc-mvp-registrado" not in sys.argv:
    sys.argv.append("--rc-mvp-registrado")
spec = importlib.util.spec_from_file_location("executar_lote_piloto", PIL / "scripts" / "executar-lote.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.AQUI = AQUI
m.main()
