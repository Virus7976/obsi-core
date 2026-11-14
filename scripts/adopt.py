"""Moves shared files out of one Obsi app and into obsi-core.

    python adopt.py <app repo> <app package> <source|drop> file1.kt file2.kt ... [--models A,B,C]

source: copy each file into core (package renamed), then remove it from the app.
drop:   the core already has the file; only remove it from the app.
Every app source and test file then gets star imports of the core packages, and explicit imports or
full names of a moved symbol point at the core.
"""
import os
import re
import sys

CORE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "java", "app", "obsi", "core")
CORE_PKGS = ["", "data", "obsidian", "ui", "update", "link", "share"]


def read(p):
    with open(p, encoding="utf-8", newline="") as f:
        return f.read()


def write(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


DECL = re.compile(r"^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|inline|data|sealed|enum|abstract|open|annotation|value|const|operator|infix|tailrec|suspend)\s+)*(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)", re.M)


def top_names(text):
    names = set()
    for m in DECL.finditer(text):
        line_start = text.rfind("\n", 0, m.start()) + 1
        if m.start() != line_start:
            continue
        if text[m.start():m.end()].lstrip().startswith("private"):
            continue
        names.add(m.group(1))
    return names


def extract_models(models_path, names):
    """Cuts the named @Serializable classes (and their doc comments) out of Models.kt."""
    text = read(models_path)
    cut = []
    for name in names:
        m = re.search(r"((?:/\*\*(?:[^*]|\*(?!/))*\*/\s*\n)?(?:@Serializable\s*\n)?(?:data class|class|val) " + re.escape(name) + r"\b)", text)
        if not m:
            raise SystemExit(f"model {name} not found")
        start = m.start()
        # The block ends at the first line after it that starts a new top-level thing.
        rest = text[m.end():]
        depth = 0
        i = m.end()
        end = None
        for j, ch in enumerate(rest):
            if ch in "({[":
                depth += 1
            elif ch in ")}]":
                depth -= 1
            elif ch == "\n" and depth == 0:
                nxt = rest[j + 1:j + 2]
                if nxt not in (" ", "\t", ")", "}", "]", ".") :
                    end = i + j + 1
                    break
        if end is None:
            end = len(text)
        cut.append(text[start:end])
        text = text[:start] + text[end:]
    write(models_path, re.sub(r"\n{3,}", "\n\n", text))
    return cut


def main():
    args = sys.argv[1:]
    models = []
    if "--models" in args:
        i = args.index("--models")
        models = args[i + 1].split(",")
        args = args[:i] + args[i + 2:]
    repo, pkg, mode, files = args[0], args[1], args[2], args[3:]
    src = os.path.join(repo, "app", "src", "main", "java", *pkg.split("."))
    moved = {}

    for rel in files:
        p = os.path.join(src, rel)
        sub = os.path.dirname(rel).replace("/", ".")
        core_pkg = "app.obsi.core" + ("." + sub if sub else "")
        if mode == "source":
            text = read(p)
            text = text.replace(f"package {pkg}" + ("." + sub if sub else ""), f"package {core_pkg}", 1)
            text = re.sub(r"^internal ", "", text, flags=re.M)
            write(os.path.join(CORE, rel), text)
        core_text = read(os.path.join(CORE, rel))
        for n in top_names(core_text):
            moved[n] = core_pkg
        if os.path.exists(p):
            os.remove(p)

    if models:
        models_path = os.path.join(src, "data", "Models.kt")
        cut = extract_models(models_path, models)
        target = os.path.join(CORE, "data", "SharedModels.kt")
        if mode == "source":
            head = "package app.obsi.core.data\n\nimport kotlinx.serialization.Serializable\n\n"
            body = read(target)[len(head):] if os.path.exists(target) else ""
            write(target, head + body + "\n".join(c.rstrip() + "\n" for c in cut))
        for n in models:
            moved[n] = "app.obsi.core.data"

    # Every name the core has, so imports are right even for files moved on an earlier run.
    for root, _, names in os.walk(CORE):
        for name in names:
            if name.endswith(".kt"):
                text = read(os.path.join(root, name))
                m = re.search(r"^package ([\w.]+)", text, re.M)
                for n in top_names(text):
                    moved.setdefault(n, m.group(1))

    stars = "".join(f"import app.obsi.core{'.' + s if s else ''}.*\n" for s in CORE_PKGS)
    for base in (os.path.join(repo, "app", "src", "main", "java"), os.path.join(repo, "app", "src", "test", "java")):
        for root, _, names in os.walk(base):
            for name in names:
                if not name.endswith(".kt"):
                    continue
                p = os.path.join(root, name)
                text = read(p)
                orig = text
                for n, cp in moved.items():
                    text = re.sub(r"\b" + re.escape(pkg) + r"\.\w+\." + re.escape(n) + r"\b", cp + "." + n, text)
                    text = re.sub(r"\b" + re.escape(pkg) + r"\." + re.escape(n) + r"\b", cp + "." + n, text)
                if "import app.obsi.core.*" not in text:
                    m = re.search(r"^package .*\n", text, re.M)
                    if m:
                        text = text[:m.end()] + "\n" + stars + text[m.end():]
                # An explicit import of the same name twice is an error.
                seen = set()
                out = []
                for line in text.split("\n"):
                    if line.startswith("import "):
                        if line in seen:
                            continue
                        seen.add(line)
                    out.append(line)
                text = "\n".join(out)
                if text != orig:
                    write(p, text)
    print("moved", len(moved), "names")


main()
