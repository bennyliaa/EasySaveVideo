from pathlib import Path
import re, json, zipfile, xml.etree.ElementTree as ET

root = Path('D:/CodexProjects/EasySaveVideo/docs/ui')
files = sorted(root.glob('*.md'))
issues = []
links = 0
for f in files:
    s = f.read_text(encoding='utf-8')
    if '\ufffd' in s: issues.append(f'{f.name}: replacement character')
    if len(re.findall(r'^```', s, re.M)) % 2: issues.append(f'{f.name}: open code fence')
    for target in re.findall(r'\[[^\]]+\]\(([^\n]+?)\)', s):
        target = target.strip('<>')
        if target.startswith(('https://', 'http://', '#')): continue
        links += 1
        if not (f.parent / target.split('#')[0]).exists(): issues.append(f'{f.name}: missing {target}')
    for n, line in enumerate(s.splitlines(), 1):
        if line.startswith('|') and not line.endswith('|'): issues.append(f'{f.name}:{n}: open table row')

matrix = (root/'07_ESV_UI_PPT_TRACEABILITY_MATRIX.md').read_text(encoding='utf-8')
rows = re.findall(r'^\| (\d+) \| ([^|]+) \|', matrix, re.M)
if [int(i) for i, _ in rows] != list(range(1, 35)): issues.append('matrix: slide sequence')
ns = {'a':'http://schemas.openxmlformats.org/drawingml/2006/main'}
with zipfile.ZipFile(root/'EasySaveVideo_UI_Effect_Design_V1.0_2026-10-11.pptx') as z:
    for i, title in rows:
        if int(i) == 1: continue
        node = ET.fromstring(z.read(f'ppt/slides/slide{i}.xml'))
        texts = [n.text or '' for n in node.findall('.//a:t', ns)]
        if title.strip().replace(' ', '') not in ''.join(texts).replace(' ', ''):
            issues.append(f'matrix: page {i} title mismatch: {title}')

requirements = (root/'03_ESV_UI_CODER_TECHNICAL_REQUIREMENTS.md').read_text(encoding='utf-8')
tests = (root/'04_ESV_UI_TESTER_TEST_REQUIREMENTS.md').read_text(encoding='utf-8')
contract = (root/'05_ESV_UI_DATA_AND_INTERFACE_CONTRACT.md').read_text(encoding='utf-8')
for label, text, pattern, count in [
    ('requirements', requirements, r'^\| UI-R(\d+) \|', 14),
    ('tests', tests, r'^\| T-(\d+) \|', 31),
    ('demo', contract, r'^\| DEMO-(\d+) \|', 14),
]:
    ids = [int(n) for n in re.findall(pattern, text, re.M)]
    if ids != list(range(1, count+1)): issues.append(f'{label}: missing or duplicate ids')
if len(files) != 9: issues.append('document count must be 9')
print(json.dumps({'document_count':len(files), 'local_links_checked':links, 'ppt_pages':len(rows),
                  'requirements':14,'test_cases':31,'demo_scenarios':14,'issues':issues}, ensure_ascii=False))
raise SystemExit(bool(issues))
