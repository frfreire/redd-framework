import hashlib, pathlib, sys
raiz = pathlib.Path(sys.argv[1]); ok = bad = 0
for b in sorted(raiz.rglob("*-base-a/mvp-results.csv")):
    a = pathlib.Path(str(b.parent).replace("-base-a", "-atual-a")) / b.name
    db, da = b.read_bytes(), a.read_bytes()
    cb = sum(1 for l in db.splitlines() if l.startswith(b"#")); ca = sum(1 for l in da.splitlines() if l.startswith(b"#"))
    igual = db == da
    ok += igual; bad += (not igual)
    print(f"{b.parent.parent.parent.name}/{b.parent.parent.name} bytes={len(db)}/{len(da)} igual={igual} hash={hashlib.sha256(db).hexdigest()[:10]} # base={cb} atual={ca}")
print(f"TOTAL byte-a-byte iguais={ok} diferentes={bad}")
