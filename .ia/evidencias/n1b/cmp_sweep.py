import os, subprocess, sys, pathlib, hashlib
t = pathlib.Path(sys.argv[1]); atual = pathlib.Path(sys.argv[2]).resolve(); base = t / "base-325f4e3"
def cp(raiz): return str(raiz / "target" / "classes") + os.pathsep + (raiz / "target" / "cp.txt").read_text().strip()
casos = [("VOTING", 10), ("POS", 12), ("POSA", 12), ("POW", 12), ("LONGEST", 12)]
casos = [(ty, n) for ty, n in casos]
ok = bad = skip = 0
saida = t / "n1b"; saida.mkdir(exist_ok=True)
for ty, n in casos:
    for seed in (1, 2):
        res = {}
        for lado, raiz in (("base", base), ("atual", atual)):
            out = saida / f"{ty}-{n}-s{seed}-{lado}.csv"
            if out.exists(): out.unlink()
            cmd = ["java", "-cp", cp(raiz), "jabs.example.ByzantineSweep", f"--type={ty}", f"--validators={n}", "--max-byz=3",
                   "--trials=1", "--duration=30", f"--seed={seed}", f"--out={out}"]
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=600, cwd=str(raiz))
            res[lado] = (r.returncode, out.read_bytes() if out.exists() else None, (r.stdout + r.stderr)[-160:].replace("\n", " "))
        b, a = res["base"], res["atual"]
        if b[1] is None or a[1] is None:
            skip += 1; print(f"{ty} n={n} s={seed}: SEM_CSV base rc={b[0]} atual rc={a[0]} | base: {b[2]} | atual: {a[2]}"); continue
        igual = b[1] == a[1]; ok += igual; bad += (not igual)
        nc = sum(1 for l in a[1].splitlines() if l.startswith(b"#"))
        print(f"{ty} n={n} s={seed}: igual={igual} bytes={len(b[1])}/{len(a[1])} #atual={nc} sha={hashlib.sha256(b[1]).hexdigest()[:10]} rc={b[0]}/{a[0]}")
print(f"TOTAL iguais={ok} diferentes={bad} sem_csv={skip}")
