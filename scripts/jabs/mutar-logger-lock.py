#!/usr/bin/env python3
"""Prova de sensibilidade a mutacao do teste AbstractCSVLoggerCorridaTest (tarefa LOGGER-csv-flushBuffer-corrida).

Reproduzivel por qualquer pessoa, da raiz do repositorio:
    python3 scripts/jabs/mutar-logger-lock.py [--mvn "mvn -o"] [--repeticoes 1]

Passos:
  1. baseline: roda `mvn test -Dtest=AbstractCSVLoggerCorridaTest` com o codigo atual; DEVE passar.
  2. mutacoes (cada uma aplicada sozinha sobre o fonte original; o mesmo teste DEVE falhar em cada uma):
       M1 "todo o lock": remove o `synchronized (writeLock)` de flushBuffer(int) (poll e escrita voltam a correr
          sem exclusao mutua, como no defeito original);
       M2 "so o drain final": mantem o lock em flushBuffer(int) (thread regular e lote de 128) e troca a chamada
          `flushBuffer(Integer.MAX_VALUE)` de stopFlusherAndFlush() por uma copia do corpo SEM lock. Exercita o
          caminho em que o join de 1 s expira com o flusher ainda dentro do writer.
        M3 "sem drain final": remove a chamada `flushBuffer(Integer.MAX_VALUE)` de stopFlusherAndFlush() (o
           esvaziamento explicito da fila no fechamento), mantendo o lock e o resto. Linhas que o flusher nao
           retirou antes do close() se perdem; o teste com flusher dormindo e writer que rejeita escrita apos close()
           tem de detectar isso.
     Imprime o resultado por mutacao ("DETECTADA" / "NAO DETECTADA").
  3. restaura o arquivo byte a byte (try/finally, tambem em SIGINT/SIGTERM) e confere o sha256. Uma copia fica em
     /tmp/mutar-logger-lock.bak caso o processo seja morto a forca (kill -9): restaure com
     `cp /tmp/mutar-logger-lock.bak src/main/java/jabs/log/AbstractCSVLogger.java`.
Codigo de saida: 0 = baseline passou E TODOS os mutantes falharam (teste sensivel); 1 = algum mutante passou ou
baseline falhou; 2 = trecho a mutar nao encontrado (script desatualizado em relacao ao fonte).
Nao usa git (o arquivo pode ter alteracoes nao commitadas): a restauracao e pelos bytes lidos no inicio.
"""
import argparse
import hashlib
import os
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
from pathlib import Path


def mvn_exe():
    """Executavel do Maven: mvn.cmd no Windows (subprocess sem shell nao resolve `mvn`), mvn fora dele."""
    if os.name == "nt":
        return shutil.which("mvn.cmd") or "mvn.cmd"
    return "mvn"


ALVO = Path("src/main/java/jabs/log/AbstractCSVLogger.java")
BACKUP = Path(tempfile.gettempdir()) / "mutar-logger-lock.bak"  # no Linux: /tmp/mutar-logger-lock.bak
# Ancora: o bloco synchronized de flushBuffer(int) (o unico que abre com `boolean wrote = false;`).
VELHO = "        synchronized (writeLock) {\n            boolean wrote = false;"
NOVO = "        {   // MUTACAO: lock removido\n            boolean wrote = false;"
# M2: o drain final, sem lock (copia do corpo de flushBuffer sem o synchronized)
VELHO2 = "        flushBuffer(Integer.MAX_VALUE);\n    }"
NOVO2 = (
    "        {   // MUTACAO: drain final sem lock\n"
    "            boolean wrote = false;\n"
    "            for (;;) {\n"
    "                String[] row = csvRowBuffer.poll();\n"
    "                if (row == null) break;\n"
    "                try {\n"
    "                    loggerCSV.writeRow(row);\n"
    "                    wrote = true;\n"
    "                } catch (UncheckedIOException e) {\n"
    "                    recordFailure(e);\n"
    "                }\n"
    "            }\n"
    "            if (wrote) {\n"
    "                try {\n"
    "                    if (underlyingWriter != null) underlyingWriter.flush();\n"
    "                } catch (IOException e) {\n"
    "                    recordFailure(e);\n"
    "                }\n"
    "            }\n"
    "        }\n"
    "    }"
)
# M3: sem o drain final explicito (a linha do comentario mantem a ancora unica e a assinatura do metodo)
NOVO3 = "        // MUTACAO: drain final removido\n    }"
MUTACOES = [
    ("M1 todo o lock (flushBuffer sem synchronized)", VELHO, NOVO),
    ("M2 so o drain final (flushBuffer da thread regular mantem o lock)", VELHO2, NOVO2),
    ("M3 sem drain final explicito (stopFlusherAndFlush nao esvazia a fila)", VELHO2, NOVO3),
]


def rodar(mvn, repeticoes):
    cmd = shlex.split(mvn)
    if cmd and cmd[0] == "mvn":
        cmd[0] = mvn_exe()
    cmd = cmd + ["test", "-Dtest=AbstractCSVLoggerCorridaTest"]
    saida = ""
    rc = 0
    for _ in range(repeticoes):
        r = subprocess.run(cmd, capture_output=True, text=True)
        saida += r.stdout + r.stderr
        rc = r.returncode
        if rc != 0:
            break
    return rc, saida


def resumo(saida):
    chaves = ("Tests run:", "FAIL", "duas threads", "linha ", "numero de linhas", "BUILD")
    linhas = [l for l in saida.splitlines() if any(k in l for k in chaves)]
    return "\n".join(l[:200] for l in linhas[:12])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mvn", default="mvn -o", help="comando base do maven (padrao: 'mvn -o')")
    ap.add_argument("--repeticoes", type=int, default=1, help="execucoes de cada mutante ate falhar (padrao 1)")
    a = ap.parse_args()

    if not ALVO.exists():
        print(f"ERRO: {ALVO} nao existe; rode da raiz do repositorio")
        return 2
    original = ALVO.read_bytes()
    sha = hashlib.sha256(original).hexdigest()
    texto = original.decode("utf-8").replace("\r\n", "\n")  # ancoras usam LF; a arvore pode estar em CRLF
    for nome, velho, _ in MUTACOES:
        if texto.count(velho) != 1:
            print(f"ERRO: ancora de '{nome}' encontrada {texto.count(velho)} vez(es) (esperado 1); atualize o script")
            return 2
    BACKUP.write_bytes(original)

    def restaurar(*_):
        ALVO.write_bytes(original)

    def ao_sinal(signum, _frame):
        restaurar()
        sys.exit(130)

    signal.signal(signal.SIGINT, ao_sinal)
    signal.signal(signal.SIGTERM, ao_sinal)
    resultados = []
    try:
        print("== baseline (codigo atual, com lock) ==")
        rc0, out0 = rodar(a.mvn, 1)
        print(resumo(out0))
        print(f"baseline: {'PASSOU' if rc0 == 0 else 'FALHOU'} (rc={rc0})")

        for i, (nome, velho, novo) in enumerate(MUTACOES, 1):
            print(f"== mutante {i}/{len(MUTACOES)}: {nome} ==")
            ALVO.write_text(texto.replace(velho, novo), encoding="utf-8")
            rc, out = rodar(a.mvn, a.repeticoes)
            print(resumo(out))
            resultados.append((nome, rc != 0))
            restaurar()  # sempre volta ao original antes do proximo mutante
    finally:
        restaurar()
    ok_restaurado = hashlib.sha256(ALVO.read_bytes()).hexdigest() == sha
    print(f"arquivo restaurado, sha256 identico ao inicial: {ok_restaurado}")
    if not ok_restaurado:
        print(f"ERRO: restauracao divergente; copia em {BACKUP}")
        return 1
    print("== resultado por mutacao ==")
    for nome, detectada in resultados:
        print(f"  {'DETECTADA     ' if detectada else 'NAO DETECTADA'}  {nome}")
    if rc0 != 0:
        print("INVALIDO: o baseline ja falha; corrija antes de avaliar as mutacoes")
        return 1
    if all(d for _, d in resultados):
        print("FALHA ESPERADA em todas: o teste e sensivel a mutacao")
        return 0
    print("TESTE INSENSIVEL: ao menos um mutante passou; o teste nao detecta aquela remocao de lock")
    return 1


if __name__ == "__main__":
    sys.exit(main())
