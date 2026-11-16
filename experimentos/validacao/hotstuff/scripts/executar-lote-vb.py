#!/usr/bin/env python3
"""V-B (EMENDA E7): executa o lote de V-B em paralelo (<= 8 JVMs). Arquivo NOVO: o executar-lote.py desta pasta RECUSA o bloco vb
(e seu hash vale para V-A/V-C ja commitados), entao este reaproveita o executar-lote do piloto com o rodar_celula.py DESTA pasta
(build /tmp/opencode/val-hs) e SEMPRE com --rc-mvp-registrado: rc_mvp != 0 e REGISTRADO, nao e erro de execucao.
ATENCAO: o codigo de saida 3 do lote e ESPERADO quando ha celulas que abortam pela regra do ataque (rc_observador != 0); a
classificacao de verdade e de analisar-vb.py. Nenhuma celula e reexecutada nem substituida por outra semente.
Uso: executar-lote-vb.py <dir_celulas_vb> --saida <dir_brutos_vb> [--paralelo 8] [--pular-existentes]
NAO executar antes da etapa 3b (commit desta emenda). Este arquivo, por si so, nao simula nada.
"""
import importlib.util
import json
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
PIL = AQUI.parents[2] / "piloto-timeout-hotstuff"
man = json.loads((Path(sys.argv[1]) / "manifesto.json").read_text())
if man["fatores"].get("bloco") != "vb" or man["n_celulas"] != 180:
    sys.exit("ERRO: so aceita o manifesto de V-B (bloco vb, 180 celulas) gerado por gerar-celulas-vb.py")
if "--rc-mvp-registrado" not in sys.argv:
    sys.argv.append("--rc-mvp-registrado")
spec = importlib.util.spec_from_file_location("executar_lote_piloto", PIL / "scripts" / "executar-lote.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.AQUI = AQUI
m.main()
