#!/usr/bin/env python3
"""Diagnostico B1 (HS-CB fase 2): compila, FORA da arvore, uma variante de AbstractCSVLogger.java para provar causa.

NAO altera src/. Copia o AbstractCSVLogger.java da raiz indicada para /tmp, aplica UMA substituicao textual e compila
so esse arquivo com javac contra <raiz>/target/classes + cp.txt, gerando <saida>/classes (para ir ANTES no
classpath do diag_determinismo_carga.py via --cp-prefixo).

Variantes:
  sincronizado : `private void flushBuffer()` -> `private synchronized void flushBuffer()`
                 (serializa poll+escrita; remove a corrida entre o flusher de 250 ms e o flush por lote de 128 da thread principal)
  sem-flusher  : startFlusherIfNeeded() vira no-op (so a thread principal escreve; sem tempo de parede no caminho)
Uso: diag_variante_logger.py --raiz /tmp/opencode/base-8c68084 --variante sincronizado --saida /tmp/opencode/b1/var-sinc
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

ALVO = "src/main/java/jabs/log/AbstractCSVLogger.java"
TROCAS = {
    "flusher-1ms": [("CSV_FLUSH_INTERVAL_MS = 250;", "CSV_FLUSH_INTERVAL_MS = 1;")],
    "flusher-1ms-sincronizado": [("CSV_FLUSH_INTERVAL_MS = 250;", "CSV_FLUSH_INTERVAL_MS = 1;"),
                                 ("    private void flushBuffer() {", "    private synchronized void flushBuffer() {")],
    "sincronizado": [("    private void flushBuffer() {", "    private synchronized void flushBuffer() {")],
    "sem-flusher": [("    private synchronized void startFlusherIfNeeded() {\n        if (flusherRunning.get()) return;",
                     "    private synchronized void startFlusherIfNeeded() {\n        if (true) return;\n        if (flusherRunning.get()) return;")],
}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raiz", required=True, help="raiz com target/classes e target/cp.txt e o fonte em src/")
    ap.add_argument("--fonte", help="raiz do fonte (padrao: o repositorio atual)", default=".")
    ap.add_argument("--variante", required=True, choices=sorted(TROCAS))
    ap.add_argument("--saida", required=True)
    a = ap.parse_args()
    raiz, saida = Path(a.raiz).resolve(), Path(a.saida)
    texto = (Path(a.fonte) / ALVO).read_text(encoding="utf-8")
    for velho, novo in TROCAS[a.variante]:
        if texto.count(velho) != 1:
            print(f"ERRO: trecho esperado nao encontrado exatamente 1 vez: {velho!r}")
            return 2
        texto = texto.replace(velho, novo)
    src = saida / "src" / "jabs" / "log"
    src.mkdir(parents=True, exist_ok=True)
    (src / "AbstractCSVLogger.java").write_text(texto, encoding="utf-8")
    cls = saida / "classes"
    cls.mkdir(parents=True, exist_ok=True)
    cp = f"{raiz / 'target' / 'classes'}{os.pathsep}{(raiz / 'target' / 'cp.txt').read_text(encoding='utf-8').strip()}"
    r = subprocess.run(["javac", "--release", "17", "-nowarn", "-cp", cp, "-d", str(cls),
                        str(src / "AbstractCSVLogger.java")], capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        return 2
    print(f"OK variante={a.variante}: {cls}/jabs/log/AbstractCSVLogger.class")
    return 0


if __name__ == "__main__":
    sys.exit(main())
