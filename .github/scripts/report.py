"""Pretvara rezultate testova i lint-a u GitHub anotacije (vidljive na stranici build-a).

Izlazni kod 1 ako neki test padne ili lint prijavi grešku (Error/Fatal).
"""
import glob
import sys
import xml.etree.ElementTree as ET


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


failed = []
total = 0
for f in glob.glob("app/build/test-results/**/*.xml", recursive=True):
    root = ET.parse(f).getroot()
    for tc in root.iter("testcase"):
        total += 1
        for bad in list(tc.findall("failure")) + list(tc.findall("error")):
            msg = (bad.get("message") or bad.text or "").strip().splitlines()[0][:300]
            failed.append(f"{tc.get('classname', '').split('.')[-1]}.{tc.get('name')}: {msg}")

if total == 0:
    print("::error title=Testovi::Nijedan test nije pokrenut (build testova nije uspeo)")
elif failed:
    print(f"::error title=Testovi: {len(failed)} od {total} palo::" + esc("\n".join(failed)))
else:
    print(f"::notice title=Testovi::Svih {total} testova prošlo")

lint_errors = []
lint_warnings = []
for f in glob.glob("app/build/reports/lint-results*.xml"):
    for issue in ET.parse(f).getroot().iter("issue"):
        loc = issue.find("location")
        where = ""
        if loc is not None:
            where = loc.get("file", "").split("/src/main/")[-1] + ":" + loc.get("line", "?")
        line = f"[{issue.get('id')}] {where} {issue.get('message')}"
        if issue.get("severity") in ("Error", "Fatal"):
            lint_errors.append(line)
        else:
            lint_warnings.append(line)

if lint_errors:
    print(f"::error title=Lint: {len(lint_errors)} grešaka::" + esc("\n".join(lint_errors[:60])))
if lint_warnings:
    print(f"::warning title=Lint: {len(lint_warnings)} upozorenja::" + esc("\n".join(lint_warnings[:80])))
if not lint_errors and not lint_warnings:
    print("::notice title=Lint::Bez primedbi")

sys.exit(1 if (failed or total == 0 or lint_errors) else 0)
