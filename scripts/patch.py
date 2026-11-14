"""Exact text swaps that keep a file's own line endings. patch.py <file> then pairs on stdin as JSON."""
import json, sys
p = sys.argv[1]
s = open(p, encoding="utf-8", newline="").read()
crlf = "\r\n" in s
s = s.replace("\r\n", "\n")
for a, b in json.load(sys.stdin):
    if a not in s:
        sys.exit(f"NOT FOUND in {p}:\n{a[:200]}")
    s = s.replace(a, b, 1)
open(p, "w", encoding="utf-8", newline="").write(s.replace("\n", "\r\n") if crlf else s)
print("ok", p)
