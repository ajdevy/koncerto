import csv, glob, os, sys, json
from pathlib import Path

# Classes excluded from aggregate coverage:
# - CLI / Spring Boot entry points
# - OS-dependent recorder coroutine continuations
# - Kotlin compiler-generated lambda/coroutine synthetic classes
EXCLUDED_CLASS_PATTERNS = (
    "DemoRecordingTrigger",
    "KoncertoApplicationKt",
    "Recorder.record.new Function2",
    "Recorder.isAvailable.new Function2",
    "new Function2()",
    "new Function3()",
    "new FlowCollector()",
    "new Comparator()",
)

def is_excluded(class_name: str) -> bool:
    return any(pattern in class_name for pattern in EXCLUDED_CLASS_PATTERNS)

total_missed = 0
total_covered = 0
branches_missed = 0
branches_covered = 0

for f in glob.glob('koncerto-*/build/reports/jacoco/test/jacocoTestReport.csv'):
    with open(f) as csvfile:
        reader = csv.DictReader(csvfile)
        for row in reader:
            if is_excluded(row['CLASS']):
                continue
            total_missed += int(row['LINE_MISSED'])
            total_covered += int(row['LINE_COVERED'])
            branches_missed += int(row['BRANCH_MISSED'])
            branches_covered += int(row['BRANCH_COVERED'])

line_pct = 100 * total_covered / (total_covered + total_missed) if total_covered + total_missed > 0 else 0
branch_pct = 100 * branches_covered / (branches_covered + branches_missed) if branches_covered + branches_missed > 0 else 0

print(f'Lines: {total_covered}/{total_covered+total_missed} = {line_pct:.1f}%')
print(f'Branches: {branches_covered}/{branches_covered+branches_missed} = {branch_pct:.1f}%')

def gen_badge(pct):
    color = '#4c1' if pct >= 90 else '#fe7d37' if pct >= 75 else '#e05d44'
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="106" height="20" role="img" aria-label="coverage: {pct:.1f}%"><linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient><clipPath id="r"><rect width="106" height="20" rx="3" fill="#fff"/></clipPath><g clip-path="url(#r)"><rect width="61" height="20" fill="#555"/><rect x="61" width="45" height="20" fill="{color}"/><rect width="106" height="20" fill="url(#s)"/></g><g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="110"><text aria-hidden="true" x="315" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)" textLength="510">coverage</text><text x="315" y="140" transform="scale(.1)" fill="#fff" textLength="510">coverage</text><text aria-hidden="true" x="825" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)" textLength="350">{pct:.1f}%</text><text x="825" y="140" transform="scale(.1)" fill="#fff" textLength="350">{pct:.1f}%</text></g></svg>'''

svg = gen_badge(line_pct)
Path('.badges/jacoco.svg').write_text(svg)
Path('.badges/coverage-summary.json').write_text(json.dumps({'branches': round(branch_pct, 2), 'coverage': round(line_pct, 2)}))

print(f'Badge generated: {line_pct:.1f}%')
if line_pct < 85.0:
    print(f'ERROR: Coverage {line_pct:.1f}% is below 85% target', file=sys.stderr)
    sys.exit(1)
