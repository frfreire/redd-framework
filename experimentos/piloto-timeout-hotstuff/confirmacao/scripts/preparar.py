#!/usr/bin/env python3
"""Prepara o build do HEAD para a CONFIRMACAO, sem tocar no build nem nos registros do piloto.

Reaproveita scripts/preparar.py do piloto (mesma logica: copia src/ pom.xml scripts/, mvn compile, classpath,
compila o observador do piloto), apenas redirecionando o destino para /tmp/opencode/confirmacao-hs e o registro de
versao para confirmacao/versao-simulador.json.
"""
import importlib.util
from pathlib import Path

AQUI = Path(__file__).resolve().parent
CONF = AQUI.parent
PIL = CONF.parent
spec = importlib.util.spec_from_file_location("preparar_piloto", PIL / "scripts" / "preparar.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.DEST = Path("/tmp/opencode/confirmacao-hs")
m.SAIDA = CONF / "versao-simulador.json"
m.main()
