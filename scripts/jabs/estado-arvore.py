#!/usr/bin/env python3
"""Imprime o estado da arvore para a evidencia de nao regressao: sha256 de `git diff <base> -- src scripts` e dos arquivos
nao rastreados de src/ e scripts/ (sem src/test/resources, que e evidencia). Uso (raiz): python3 scripts/jabs/estado-arvore.py [base=423ffbe]"""
import hashlib
import subprocess
import sys

base = sys.argv[1] if len(sys.argv) > 1 else "423ffbe"
diff = subprocess.run(["git", "diff", base, "--", "src", "scripts"], capture_output=True, check=True).stdout
print(f"git diff {base} -- src scripts | sha256sum = {hashlib.sha256(diff).hexdigest()}  "
      f"({len(diff)} bytes, {diff.count(10)} linhas)")
nr = subprocess.run(["git", "ls-files", "--others", "--exclude-standard", "src", "scripts"],
                    capture_output=True, text=True, check=True).stdout.split()
for f in sorted(x for x in nr if not x.startswith("src/test/resources/")):
    print(f"  {hashlib.sha256(open(f, 'rb').read()).hexdigest()}  {f}")
