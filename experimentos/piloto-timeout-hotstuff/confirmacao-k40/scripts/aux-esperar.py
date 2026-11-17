#!/usr/bin/env python3
"""Auxiliar de operacao (nao hasheado, nao decide nada): espera N segundos e imprime so a contagem de pastas de celula.
Nao le nenhum resultado (sem espiar dado parcial)."""
import sys, time, os
t = int(sys.argv[1]) if len(sys.argv) > 1 else 60
time.sleep(t)
d = "experimentos/piloto-timeout-hotstuff/confirmacao-k40/dados-brutos/k40"
print(time.strftime("%Y-%m-%dT%H:%M:%S%z"), "pastas:", len(os.listdir(d)) if os.path.isdir(d) else 0)
