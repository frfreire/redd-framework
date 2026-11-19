#!/usr/bin/env python3
"""Memoria persistente do enxame via Ruflo (CLI), multiplataforma.

O Ruflo NAO faz spawn de subagentes aqui: o paralelismo vem do Task tool do
OpenCode. Equivalente em Python de scripts/enxame/memoria.sh (que agora e so um
shim para este arquivo). Somente biblioteca padrao.

Uso:
  python3 scripts/enxame/memoria.py init
  python3 scripts/enxame/memoria.py store "<chave>" "<valor>"
  python3 scripts/enxame/memoria.py search "<termo>"
  python3 scripts/enxame/memoria.py get "<chave>"
  python3 scripts/enxame/memoria.py list

`store` recusa segredos e blocos grandes (saida 3): so cabe resumo de decisao.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent.parent
NAMESPACE = "redd-jabs"
DB_PATH = RAIZ / ".enxame" / "memoria-redd-jabs.db"
LIMITE_VALOR = 1200
LIMITE_LINHAS_CODIGO = 6

# Mesmos padroes do memoria.sh (grep -Ei); [[:space:]] vira \s.
PADROES_SEGREDO = [
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    r"AKIA[0-9A-Z]{16}",
    r"ghp_[A-Za-z0-9]{20,}",
    r"sk-[A-Za-z0-9]{20,}",
    r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.",
    r"(api[_-]?key|secret|password|passwd|token)\s*[=:]\s*\S{6,}",
    r"TRILHALLM_API_KEY\s*=",
]
_REGEX_SEGREDO = [(p, re.compile(p, re.IGNORECASE)) for p in PADROES_SEGREDO]
_FIM_DE_CODIGO = re.compile(r"[{};]$")

EXIT_USO = 1
EXIT_RECUSADO = 3


def _erro(msg: str) -> None:
    print(msg, file=sys.stderr)


def recusar_se_sensivel(texto: str) -> None:
    if len(texto) > LIMITE_VALOR:
        _erro(f"[memoria] RECUSADO: valor com {len(texto)} caracteres "
              f"(limite {LIMITE_VALOR}). Grave um resumo, nao codigo.")
        sys.exit(EXIT_RECUSADO)
    for padrao, rx in _REGEX_SEGREDO:
        if rx.search(texto):
            _erro("[memoria] RECUSADO: o valor parece conter segredo/material "
                  f"criptografico (padrao: {padrao}).")
            sys.exit(EXIT_RECUSADO)
    # grep -c '[{};]$' conta linhas (separador \n) terminadas em { } ou ;
    linhas_codigo = sum(1 for linha in texto.split("\n") if _FIM_DE_CODIGO.search(linha))
    if linhas_codigo > LIMITE_LINHAS_CODIGO:
        _erro("[memoria] RECUSADO: o valor parece codigo-fonte. "
              "Grave decisao + motivo + arquivo afetado.")
        sys.exit(EXIT_RECUSADO)


def _e_windows() -> bool:
    return os.name == "nt"


def _resolver_ruflo() -> list[str]:
    """ruflo no PATH, senao npx(.cmd) --yes ruflo@latest."""
    ruflo = shutil.which("ruflo")
    if ruflo:
        return [ruflo]
    npx = shutil.which("npx.cmd" if _e_windows() else "npx")
    if not npx:
        _erro("[memoria] nem 'ruflo' nem 'npx' estao no PATH; instale o Node.js.")
        sys.exit(EXIT_USO)
    if _e_windows():
        # Evita o cmd.exe (que trunca argumentos em quebra de linha e interpreta
        # %VAR% e aspas): chama node + npx-cli.js do mesmo Node, se existirem.
        pasta = Path(npx).resolve().parent
        cli = pasta / "node_modules" / "npm" / "bin" / "npx-cli.js"
        node = pasta / "node.exe"
        if cli.is_file() and node.is_file():
            return [str(node), str(cli), "--yes", "ruflo@latest"]
    return [npx, "--yes", "ruflo@latest"]


def _executar(args: list[str]) -> int:
    cmd = _resolver_ruflo() + args
    if _e_windows() and cmd[0].lower().endswith((".cmd", ".bat")):
        # Fallback via cmd.exe: aspas, % e quebras de linha seriam corrompidos.
        if any(('"' in a or "%" in a or "\n" in a or "\r" in a) for a in args):
            _erro("[memoria] RECUSADO: no Windows sem node/npx-cli.js o valor nao pode "
                  'conter aspas ("), % nem quebras de linha. Reescreva o resumo.')
            return EXIT_RECUSADO
    env = dict(os.environ)
    env["CLAUDE_FLOW_DB_PATH"] = str(DB_PATH)
    sys.stdout.flush()
    return subprocess.run(cmd, env=env, cwd=str(RAIZ)).returncode


def _uso() -> int:
    _erro("uso: memoria.py {init|store <chave> <valor>|search <termo>|get <chave>|list}")
    return EXIT_USO


def _arg(args: list[str], i: int, uso: str) -> str:
    if len(args) <= i or args[i] == "":
        _erro(f"uso: {uso}")
        sys.exit(EXIT_USO)
    return args[i]


def main(argv: list[str]) -> int:
    if not (RAIZ / "enxame.config.json").is_file():
        _erro(f"[memoria] enxame.config.json nao encontrado em {RAIZ}")
        return EXIT_USO
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(DB_PATH.parent, 0o700)
    except OSError:
        pass

    sub = argv[0] if argv else ""
    args = argv[1:]
    if sub == "init":
        return _executar(["memory", "init", "--path", str(DB_PATH)])
    if sub == "store":
        chave = _arg(args, 0, "memoria.py store <chave> <valor>")
        valor = _arg(args, 1, "memoria.py store <chave> <valor>")
        recusar_se_sensivel(chave)
        recusar_se_sensivel(valor)
        _erro(f"[memoria] gravando '{chave}' ns='{NAMESPACE}'")
        return _executar(["memory", "store", "--key", chave, "--value", valor,
                          "--namespace", NAMESPACE])
    if sub == "search":
        termo = _arg(args, 0, "memoria.py search <termo>")
        return _executar(["memory", "search", "--query", termo,
                          "--namespace", NAMESPACE, "--threshold", "0.3"])
    if sub == "get":
        chave = _arg(args, 0, "memoria.py get <chave>")
        return _executar(["memory", "retrieve", "--key", chave, "--namespace", NAMESPACE])
    if sub == "list":
        return _executar(["memory", "list", "--namespace", NAMESPACE])
    return _uso()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
