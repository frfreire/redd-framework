#!/usr/bin/env python3
"""Arquiva no repositorio a evidencia de nao regressao produzida por nao_regressao.py (criterio 7).

Le a arvore de saida de nao_regressao.py (ex.: /tmp/opencode/nr-final/<semente>/<grupo>/<PROTO>-<n>v-<dur>s-{base,atual}-{a,b}/)
e, para cada caso, copia os CSV das execucoes "base-a" e "atual-a" para <destino>/<semente>/<grupo>/<caso>/{base-a,atual-a}/.
CSV maior que --limite-bytes NAO e copiado: so sha256, bytes e linhas entram na tabela.
Tambem copia os arquivos de saida de comando (saida-*.txt) e grava <destino>/TABELA.txt com, por CSV:
bytes, linhas, sha256 de base-a e de atual-a, e se sao byte a byte iguais (INCLUINDO a coluna de hash, que o
nao_regressao.py ignora). Grava <destino>/ESTADO-ARVORE.txt com `git diff <base> -- src scripts | sha256sum`
e os arquivos nao rastreados (sha256). Le o git; so escreve em <destino>.

Uso:
  arquivar-nao-regressao.py --raiz /tmp/opencode/nr-final --destino src/test/resources/nao-regressao/2c-4-hotstuff-mvpcomparison \
      --base-commit 45eb3e7 --grupos geral,tree --sementes s1,s2
Saida: 0 ok; 2 erro.
"""
import argparse
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

# Fonte unica da lista de aditivos conhecidos (B3): a do comparador, importada, nao copiada.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from nao_regressao import ARQUIVOS_ADITIVOS  # noqa: E402


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def linhas(b: bytes) -> int:
    return b.count(b"\n") + (0 if b.endswith(b"\n") or not b else 1)


def git(*args, cwd="."):
    r = subprocess.run(["git", *args], capture_output=True, cwd=cwd)
    if r.returncode != 0:
        sys.exit(f"ERRO: git {' '.join(args)}: {r.stderr.decode(errors='replace').strip()}")
    return r.stdout


def estado_arvore(base_commit: str, destino: Path) -> str:
    comp = git("rev-parse", base_commit).decode().strip()
    diff = git("diff", base_commit, "--", "src", "scripts")
    arquivos_mod = git("diff", base_commit, "--name-only", "--", "src", "scripts").decode().split()
    nao_rastreados = git("ls-files", "--others", "--exclude-standard", "--", "src", "scripts").decode().split()
    dest_rel = str(destino)
    out = [f"base_commit={comp}",
           f"git diff {base_commit} -- src scripts | sha256sum = {sha256_bytes(diff)}  ({len(diff)} bytes, {linhas(diff)} linhas)",
           "arquivos rastreados modificados (entram no hash acima):"]
    out += [f"  {p}" for p in arquivos_mod]
    out.append("arquivos NAO rastreados em src/ e scripts/ (nao entram no hash acima), com sha256 "
               "(os que estao sob o diretorio de evidencia sao listados em TABELA.txt, nao aqui):")
    for p in nao_rastreados:
        if p.startswith(dest_rel.rstrip("/") + "/"):
            continue
        out.append(f"  {sha256_bytes(Path(p).read_bytes())}  {p}")
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raiz", required=True)
    ap.add_argument("--destino", required=True)
    ap.add_argument("--base-commit", required=True)
    ap.add_argument("--grupos", default="geral,tree")
    ap.add_argument("--sementes", default="s1,s2")
    ap.add_argument("--limite-bytes", type=int, default=200 * 1024)
    a = ap.parse_args()
    raiz, destino = Path(a.raiz), Path(a.destino)
    if not raiz.is_dir():
        print(f"ERRO: {raiz} nao existe")
        return 2
    destino.mkdir(parents=True, exist_ok=True)
    tabela = ["# semente/grupo/caso/arquivo | bytes_base | linhas_base | bytes_atual | linhas_atual | sha256_base-a | "
              "sha256_atual-a | byte_a_byte | copiado",
              "# 'linhas' = linhas fisicas do arquivo (inclui a linha '#' e o cabecalho); linhas de dados = linhas - 2 nos CSV por bloco.",
              "# 'byte_a_byte' compara os arquivos INTEIROS (inclui coluna de hash e linhas '#'); NAO e o criterio do "
              "nao_regressao.py (que ignora colunas 'hash' e linhas '#')."]
    n = 0
    for s in a.sementes.split(","):
        for g in a.grupos.split(","):
            d = raiz / s / g
            if not d.is_dir():
                print(f"AVISO: {d} ausente (grupo nao rodado para esta semente; nao arquivado)")
                continue
            casos = sorted({p.name.rsplit("-base-a", 1)[0] for p in d.iterdir()
                            if p.is_dir() and p.name.endswith("-base-a")})
            for caso in casos:
                db, da = d / f"{caso}-base-a", d / f"{caso}-atual-a"
                if not da.is_dir():
                    print(f"ERRO: {da} ausente")
                    return 2
                # rglob: casos do nao_regressao.py tem os CSV na raiz da pasta (nome simples, como antes); os do
                # nao_regressao_sweep.py (D3a-2a-2d-2) tem tambem tmp/<TIPO>/*.csv (nome relativo, subpasta preservada)
                nomes = sorted(str(p.relative_to(db)) for p in db.rglob("*.csv"))
                nomes_atual = sorted(str(p.relative_to(da)) for p in da.rglob("*.csv"))
                # HS-CB-fase2 (B3): arquivos ADITIVOS CONHECIDOS (mesma lista do nao_regressao.py) existem so no atual;
                # ficam fora do conjunto comparado e vao para a tabela como "sem base" (copia do atual-a, se couber).
                aditivos = [x for x in nomes_atual if x in ARQUIVOS_ADITIVOS]
                nomes_atual = [x for x in nomes_atual if x not in ARQUIVOS_ADITIVOS]
                if nomes != nomes_atual:
                    print(f"ERRO: {caso}: conjuntos de CSV diferem")
                    return 2
                for nome in aditivos:
                    ba = (da / nome).read_bytes()
                    copiado = len(ba) <= a.limite_bytes
                    if copiado:
                        alvo = destino / s / g / caso / "atual-a" / nome
                        alvo.parent.mkdir(parents=True, exist_ok=True)
                        alvo.write_bytes(ba)
                    tabela.append(" | ".join([f"{s}/{g}/{caso}/{nome}", "-", "-", str(len(ba)), str(linhas(ba)), "-",
                                              sha256_bytes(ba), "SEM_BASE (aditivo conhecido)",
                                              "sim (so atual-a)" if copiado else f"NAO (> {a.limite_bytes} B: so sha256)"]))
                    n += 1
                for nome in nomes:
                    bb, ba = (db / nome).read_bytes(), (da / nome).read_bytes()
                    copiado = len(bb) <= a.limite_bytes and len(ba) <= a.limite_bytes
                    if copiado:
                        for lado, dados in (("base-a", bb), ("atual-a", ba)):
                            alvo = destino / s / g / caso / lado / nome
                            alvo.parent.mkdir(parents=True, exist_ok=True)
                            alvo.write_bytes(dados)
                    tabela.append(" | ".join([f"{s}/{g}/{caso}/{nome}", str(len(bb)), str(linhas(bb)), str(len(ba)),
                                              str(linhas(ba)), sha256_bytes(bb), sha256_bytes(ba),
                                              "IGUAL" if bb == ba else "DIFERE",
                                              "sim" if copiado else f"NAO (> {a.limite_bytes} B: so sha256)"]))
                    n += 1
        for f in sorted(raiz.glob(f"saida-{s}-*.txt")):
            shutil.copyfile(f, destino / f.name)
    (destino / "TABELA.txt").write_text("\n".join(tabela) + "\n", encoding="utf-8")
    (destino / "ESTADO-ARVORE.txt").write_text(estado_arvore(a.base_commit, destino), encoding="utf-8")
    print(f"OK {n} CSV tabelados em {destino}/TABELA.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
