#!/usr/bin/env python3
"""Read-only, no-Gradle classfile audit of the exact ESV baseline plus candidates.
No candidate classes are loaded or executed. Uses only Python standard library.
"""
import argparse, collections, hashlib, io, json, re, struct, subprocess, zipfile
from pathlib import Path

EP = 'com/google/errorprone/'
BASELINE = 'f184cc91c7f3e31d7b9b39219ee8b9c9d2139fab'
CANDIDATE_MANIFEST_SHA256 = '9e0e22f2fe8c12d8ff2d2ba3647c3c6378ba80af9f9d3ec1f5a844cf8b114b19'

class Reader:
    def __init__(self, data): self.data, self.pos = data, 0
    def take(self, n):
        assert self.pos + n <= len(self.data), 'truncated classfile'
        b = self.data[self.pos:self.pos+n]; self.pos += n; return b
    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack('>H', self.take(2))[0]
    def u4(self): return struct.unpack('>I', self.take(4))[0]
    def done(self): assert self.pos == len(self.data), (self.pos, len(self.data))

def descriptor_types(text):
    return re.findall(r'L([^;<]+)', text)

def is_ep(name): return name.startswith(EP)

def parse_class(data, origin):
    r = Reader(data)
    assert r.u4() == 0xcafebabe
    minor, major = r.u2(), r.u2()
    count = r.u2(); cp = [None] * count; i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            val = r.take(r.u2()).replace(b'\xc0\x80', b'\0').decode('utf-8', 'surrogatepass')
        elif tag in (3,4): val = r.take(4).hex()
        elif tag in (5,6): val = r.take(8).hex()
        elif tag in (7,8,16,19,20): val = r.u2()
        elif tag in (9,10,11,12,17,18): val = [r.u2(), r.u2()]
        elif tag == 15: val = [r.u1(), r.u2()]
        else: raise ValueError(('unknown CP tag', tag, origin))
        cp[i] = (tag, val); i += 2 if tag in (5,6) else 1
    def utf(idx):
        assert cp[idx][0] == 1; return cp[idx][1]
    def cname(idx):
        if idx == 0: return None
        assert cp[idx][0] == 7; return utf(cp[idx][1])
    access, this, sup = r.u2(), cname(r.u2()), cname(r.u2())
    result = dict(name=this, access=access, super=sup, interfaces=[cname(r.u2()) for _ in range(r.u2())],
                  major=major, minor=minor, origin=origin, fields=[], methods=[], annotations=[],
                  type_refs=[], member_refs=[], signatures=[], defaults=[], attributes=collections.Counter(),
                  raw_ep_utf8=[], literal_ep_strings=[])
    def typ(name, loc, kind):
        if name and is_ep(name): result['type_refs'].append(dict(target=name, location=loc, kind=kind))
    def desc(value, loc, kind):
        for name in descriptor_types(value): typ(name, loc, kind)
    def elem(rr, loc):
        tag = chr(rr.u1())
        if tag in 'BCDFIJSZs':
            idx = rr.u2(); raw = cp[idx]; assert raw is not None
            return dict(tag=tag, cp_tag=raw[0], value=raw[1])
        if tag == 'e':
            type_desc, name = utf(rr.u2()), utf(rr.u2()); desc(type_desc, loc, 'annotation_enum_type')
            if type_desc.startswith('L'+EP):
                result['member_refs'].append(dict(kind='annotation_enum',owner=type_desc[1:-1], name=name, descriptor=type_desc, location=loc))
            return dict(tag=tag, type=type_desc, name=name)
        if tag == 'c':
            value = utf(rr.u2()); desc(value, loc, 'annotation_class_value'); return dict(tag=tag, descriptor=value)
        if tag == '@': return dict(tag=tag, annotation=annotation(rr, loc + ':nested'))
        if tag == '[': return dict(tag=tag, values=[elem(rr, loc+'[]') for _ in range(rr.u2())])
        raise ValueError(('unknown annotation element tag', tag, origin))
    def annotation(rr, loc):
        type_desc = utf(rr.u2()); desc(type_desc, loc, 'annotation_type')
        values = []
        for _ in range(rr.u2()):
            name = utf(rr.u2()); values.append(dict(name=name, value=elem(rr, loc+':'+name)))
        item = dict(type=type_desc, location=loc, elements=values)
        result['annotations'].append(item)
        return item
    def target_info(rr):
        tag = rr.u1()
        if tag in (0x00,0x01,0x16): rr.take(1)
        elif tag in (0x10,0x17,0x42,0x43,0x44,0x45,0x46): rr.take(2)
        elif tag in (0x11,0x12): rr.take(2)
        elif tag in (0x13,0x14,0x15): pass
        elif tag in (0x40,0x41): rr.take(6 * rr.u2())
        elif tag in (0x47,0x48,0x49,0x4a,0x4b): rr.take(3)
        else: raise ValueError(('unknown annotation target', tag, origin))
        rr.take(2 * rr.u1())
    def attrs(rr, loc, member=None):
        for _ in range(rr.u2()):
            name, length = utf(rr.u2()), rr.u4(); ar = Reader(rr.take(length)); result['attributes'][name] += 1
            where = loc + ':' + name
            if name in ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations'):
                for j in range(ar.u2()): annotation(ar, where+f'[{j}]')
            elif name in ('RuntimeVisibleParameterAnnotations', 'RuntimeInvisibleParameterAnnotations'):
                for p in range(ar.u1()):
                    for j in range(ar.u2()): annotation(ar, where+f'[param{p}][{j}]')
            elif name in ('RuntimeVisibleTypeAnnotations', 'RuntimeInvisibleTypeAnnotations'):
                for j in range(ar.u2()): target_info(ar); annotation(ar, where+f'[{j}]')
            elif name == 'AnnotationDefault':
                default = elem(ar, where); assert member is not None
                member['default'] = default; result['defaults'].append(dict(location=where, value=default))
            elif name == 'Code':
                ar.take(4); ar.take(ar.u4()); ar.take(8 * ar.u2()); attrs(ar, where)
            elif name == 'Signature':
                sig = utf(ar.u2()); result['signatures'].append(dict(location=where, signature=sig)); desc(sig, where, 'signature')
                if member is not None: member['signature'] = sig
            elif name == 'ConstantValue':
                idx = ar.u2(); val = cp[idx]
                if val[0] == 8: val = (8, utf(val[1]))
                assert member is not None; member['constant'] = val
            elif name in ('LocalVariableTable','LocalVariableTypeTable'):
                for j in range(ar.u2()):
                    ar.take(4); ar.u2(); d= utf(ar.u2()); ar.u2(); desc(d, where+f'[{j}]', name)
            elif name == 'Exceptions':
                exceptions = [cname(ar.u2()) for _ in range(ar.u2())]
                if member is not None: member['exceptions'] = exceptions
            else:
                # Symbolic classes/members in all other standard attributes are CP-indexed
                # and scanned below. Unknown attributes are counted and not executed.
                ar.take(length)
            ar.done()
    for kind in ('fields','methods'):
        for _ in range(r.u2()):
            member = dict(access=r.u2(), name=utf(r.u2()), descriptor=utf(r.u2()))
            loc = kind + ':' + member['name'] + member['descriptor']
            desc(member['descriptor'], loc, 'declared_descriptor'); attrs(r, loc, member)
            result[kind].append(member)
    attrs(r, 'class'); r.done()
    for idx, entry in enumerate(cp):
        if not entry: continue
        tag, val = entry; loc = f'constant_pool[{idx}]'
        if tag == 7:
            name = cname(idx)
            if name.startswith('['): desc(name, loc, 'cp_array_class')
            else: typ(name, loc, 'cp_class')
        elif tag in (9,10,11):
            owner = cname(val[0]); nt = cp[val[1]]; assert nt[0] == 12
            name, d = utf(nt[1][0]), utf(nt[1][1]); desc(d, loc, 'cp_member_descriptor')
            if is_ep(owner):
                result['member_refs'].append(dict(kind={9:'field',10:'method',11:'interface_method'}[tag],owner=owner,name=name,descriptor=d,location=loc))
        elif tag == 12: desc(utf(val[1]), loc, 'cp_name_and_type_descriptor')
        elif tag == 16: desc(utf(val), loc, 'cp_method_type_descriptor')
        elif tag == 1 and (EP in val or EP.replace('/','.') in val):
            result['raw_ep_utf8'].append(dict(location=loc, value=val))
        elif tag == 8 and (EP in utf(val) or EP.replace('/','.') in utf(val)):
            result['literal_ep_strings'].append(dict(location=loc, value=utf(val)))
    return result

def sha(data): return hashlib.sha256(data).hexdigest()
def read_tsv(text):
    return [line.split('\t') for line in text.splitlines() if line and not line.startswith('#')]
def git_read(repo, path):
    return subprocess.check_output(['git','-C',str(repo),'show',BASELINE+':'+path], text=True)

def classes_in_artifact(path):
    with zipfile.ZipFile(path) as z:
        if path.suffix == '.jar':
            for item in z.infolist():
                if item.filename.endswith('.class'): yield item.filename, z.read(item)
        else:
            for item in z.infolist():
                # AAR runtime payload: classes.jar plus optional libs/*.jar. lint.jar is build tooling.
                if item.filename == 'classes.jar' or (item.filename.startswith('libs/') and item.filename.endswith('.jar')):
                    with zipfile.ZipFile(io.BytesIO(z.read(item))) as nested:
                        for cls in nested.infolist():
                            if cls.filename.endswith('.class'): yield item.filename+'!/'+cls.filename, nested.read(cls)

def canonical_api(cls):
    # Includes all declared members, even private/package-private, to report stronger equality.
    # Code and debug text are intentionally not API. Synthetic/bridge flags are retained.
    def member(m): return {key:value for key,value in m.items() if key in ('name','descriptor','access','signature','constant','default','exceptions')}
    return dict(name=cls['name'], access=cls['access'], super=cls['super'], interfaces=cls['interfaces'],
                fields=sorted([member(m) for m in cls['fields']], key=lambda m:(m['name'],m['descriptor'])),
                methods=sorted([member(m) for m in cls['methods']], key=lambda m:(m['name'],m['descriptor'])),
                annotations=cls['annotations'], signatures=cls['signatures'])

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_repo=next((p for p in Path(__file__).resolve().parents if (p/'settings.gradle.kts').is_file() and (p/'AGENTS.md').is_file()), None)
    parser.add_argument('--repo', type=Path, default=default_repo, required=default_repo is None)
    parser.add_argument('--bundle', type=Path, help='Optional original sealed bundle; by default use the repository candidate import map')
    parser.add_argument('--output', '--out', dest='out', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('--inventory', type=Path, help='Directory containing debug/releaseRuntimeClasspath-inventory.tsv; default REPO/app/build/reports')
    parser.add_argument('--actual-debug', type=Path, help='Current debug inventory; default: REPO/app/build/reports/debugRuntimeClasspath-inventory.tsv')
    parser.add_argument('--actual-release', type=Path, help='Current release inventory; default: REPO/app/build/reports/releaseRuntimeClasspath-inventory.tsv')
    parser.add_argument('--negative-control', choices=['remove_class','rename_element','wrong_element_descriptor','remove_default','remove_enum_constant'], help='Mutate only the parsed in-memory replacement model; never changes a JAR')
    a = parser.parse_args(); a.out.mkdir(parents=True, exist_ok=True)
    a.repo=a.repo.resolve()
    failures, notes = [], []
    import_map_path=a.repo/'docs/m5-vault-dependencies/evidence/candidate-import-map.json'
    import_map=None if a.bundle else {x['candidate_path']:x for x in json.loads(import_map_path.read_text())['files']}
    def candidate_file(name):
        if a.bundle: path=a.bundle/name
        else:
            row=import_map[name]; path=(a.repo/row['repository_path']).resolve()
            assert path.is_relative_to(a.repo), 'candidate import map escapes repository'
            data=path.read_bytes(); assert len(data)==row['bytes'] and sha(data)==row['sha256'], ('candidate import mapping mismatch',name)
        return path
    manifest_path=candidate_file('IMPORT_MANIFEST.json')
    assert sha(manifest_path.read_bytes())==CANDIDATE_MANIFEST_SHA256, 'candidate manifest anchor mismatch'
    manifest={r['path']:r for r in json.loads(manifest_path.read_text())['files']}
    def payload_file(name):
        path=candidate_file(name); data=path.read_bytes(); row=manifest[name]
        assert len(data)==row['bytes'] and sha(data)==row['sha256'], ('sealed candidate payload mismatch',name)
        return path
    old_text = git_read(a.repo, 'third_party/admitted-runtime.tsv'); baseline_rows = read_tsv(old_text)
    debug = read_tsv(git_read(a.repo, 'docs/m5a3/evidence/debugRuntimeClasspath-inventory.tsv'))
    release = read_tsv(git_read(a.repo, 'docs/m5a3/evidence/releaseRuntimeClasspath-inventory.tsv'))
    assert sorted({tuple(r[:3]) for r in debug if not r[0].startswith('com.easysavevideo:')}) == sorted(tuple(r) for r in baseline_rows)
    assert sorted({tuple(r[:3]) for r in release if not r[0].startswith('com.easysavevideo:')}) == sorted(tuple(r) for r in baseline_rows)
    assert {tuple(r[:3]) for r in debug} == {tuple(r[:3]) for r in release}
    assert payload_file('baseline-admitted-runtime-72.tsv').read_text() == old_text
    assert len(baseline_rows) == 72
    baseline_by_co={r[0]:r for r in debug}
    candidate_rows=read_tsv(payload_file('candidate-runtime-delta.tsv').read_text())
    candidate_sources={}
    for co, file, expected in candidate_rows:
        group,artifact,version=co.split(':')
        p=payload_file('candidate-maven/'+group.replace('.','/')+'/'+artifact+'/'+version+'/'+file)
        actual=sha(p.read_bytes()); assert actual==expected
        candidate_sources[co]=str(p)
    inventory_dir=a.inventory or a.repo/'app/build/reports'
    actual_paths={'debug':a.actual_debug or inventory_dir/'debugRuntimeClasspath-inventory.tsv',
                  'release':a.actual_release or inventory_dir/'releaseRuntimeClasspath-inventory.tsv'}
    actual_reports={}; actual_rows={}
    for variant,path in actual_paths.items():
        data=path.read_bytes(); rows=read_tsv(data.decode('utf-8')); actual_rows[variant]=rows
        actual_reports[variant]=dict(path=str(path.resolve()),sha256=sha(data),rows=len(rows),unique_artifacts=len({tuple(r[:3]) for r in rows}))
    assert {tuple(r[:3]) for r in actual_rows['debug']}=={tuple(r[:3]) for r in actual_rows['release']}, 'actual debug/release graph mismatch'
    old_co='com.google.errorprone:error_prone_annotations:2.28.0'
    expected={tuple(r) for r in baseline_rows if r[0]!=old_co}|{tuple(r) for r in candidate_rows}
    assert {tuple(r[:3]) for r in actual_rows['debug'] if not r[0].startswith('com.easysavevideo:')}==expected, 'actual external graph differs from exact approved projection'
    assert {tuple(r[:2]) for r in actual_rows['debug'] if r[0].startswith('com.easysavevideo:')}=={tuple(r[:2]) for r in debug if r[0].startswith('com.easysavevideo:')}, 'unexpected project artifact set'
    inputs=[]
    for co,file,expected_hash,path in sorted({tuple(r) for r in actual_rows['debug']}):
        p=Path(path); actual_hash=sha(p.read_bytes()); assert actual_hash==expected_hash, ('actual artifact hash mismatch',co)
        scope='candidate' if co in candidate_sources else ('project' if co.startswith('com.easysavevideo:') else 'baseline')
        item=dict(scope=scope,coordinate=co,filename=file,sha256=actual_hash,path=str(p),in_actual_graph=True,
                  comparison_source_path=candidate_sources[co] if co in candidate_sources else baseline_by_co[co][3])
        if scope=='project': item['matches_baseline_hash']=actual_hash==baseline_by_co[co][2]
        inputs.append(item)
    co,file,expected_hash,recorded_path=baseline_by_co[old_co]
    # The old comparison JAR remains in the repository vendor tree even after substitution.
    group,artifact,version=co.split(':')
    canonical_old=a.repo/'vendor/media3-maven'/group.replace('.','/')/artifact/version/file
    p=canonical_old if canonical_old.exists() else Path(recorded_path)
    assert sha(p.read_bytes())==expected_hash, 'old API comparison artifact hash mismatch'
    inputs.append(dict(scope='baseline',coordinate=co,filename=file,sha256=expected_hash,path=str(p),in_actual_graph=False,comparison_source_path=recorded_path))
    assert sum(i['in_actual_graph'] for i in inputs)==83
    all_classes=[]; inventory=[]
    for item in inputs:
        classes=[]; majors=collections.Counter()
        for entry, data in classes_in_artifact(Path(item['path'])):
            cls=parse_class(data,dict(coordinate=item['coordinate'],scope=item['scope'],entry=entry))
            cls['sha256']=sha(data); classes.append(cls); majors[cls['major']]+=1
        all_classes.extend(classes)
        inventory.append(dict(**item,classes=len(classes),bytecode_majors=dict(majors)))
    old_co='com.google.errorprone:error_prone_annotations:2.28.0'
    new_co='com.google.errorprone:error_prone_annotations:2.41.0'
    old={c['name']:c for c in all_classes if c['origin']['coordinate']==old_co and is_ep(c['name'])}
    new={c['name']:c for c in all_classes if c['origin']['coordinate']==new_co and is_ep(c['name'])}
    assert len(old)>0 and len(new)>0
    if a.negative_control == 'remove_class': del new[EP+'annotations/CanIgnoreReturnValue']
    elif a.negative_control in ('rename_element','wrong_element_descriptor'):
        m=next(m for m in new[EP+'annotations/concurrent/GuardedBy']['methods'] if m['name']=='value')
        if a.negative_control=='rename_element': m['name']='notValue'
        else: m['descriptor']='()I'
    elif a.negative_control == 'remove_default':
        del next(m for m in new[EP+'annotations/Immutable']['methods'] if m['name']=='containerOf')['default']
    elif a.negative_control == 'remove_enum_constant':
        new[EP+'annotations/Modifier']['fields']=[m for m in new[EP+'annotations/Modifier']['fields'] if m['name']!='FINAL']
    compare=[]
    for name,oc in sorted(old.items()):
        nc=new.get(name)
        if not nc: failures.append(dict(kind='old_class_removed',name=name)); continue
        before,after=canonical_api(oc),canonical_api(nc)
        changes={k:dict(old=before[k],new=after[k]) for k in before if before[k]!=after[k]}
        compare.append(dict(class_name=name,byte_identical=oc['sha256']==nc['sha256'],old_sha256=oc['sha256'],new_sha256=nc['sha256'],changed_structural_properties=changes))
        # Public/protected API must be exact, including class kind and hierarchy.
        for key in ('access','super','interfaces'):
            if before[key]!=after[key]: failures.append(dict(kind='old_class_structure_changed',name=name,property=key))
        for kind in ('fields','methods'):
            table={(m['name'],m['descriptor']):m for m in nc[kind]}
            for m in oc[kind]:
                if not m['access'] & 0x0005: continue
                replacement=table.get((m['name'],m['descriptor']))
                if replacement is None: failures.append(dict(kind='old_member_missing',owner=name,member=m))
                elif replacement!=m: failures.append(dict(kind='old_member_changed',owner=name,old=m,new=replacement))
        if before['annotations']!=after['annotations']: notes.append(dict(kind='annotation_metadata_changed',class_name=name,changes=changes.get('annotations')))
    # A replacement-only universe catches missing classes, exact member descriptors,
    # annotation element names/types, omitted required elements, and enum constants.
    occurrences=[]; annotations=[]; members=[]; literals=[]; raw_residual=[]
    ref_kinds=collections.Counter(); attributes=collections.Counter()
    def failure(kind, cls, **kw): failures.append(dict(kind=kind,origin=cls['origin'],class_name=cls['name'],**kw))
    def resolve_member(owner,name,d,kind):
        cls=new.get(owner)
        if cls is None: return None
        # Current annotations have no EP superclass methods. Exact declared lookup is
        # stricter than inherited resolution and prevents a false compatibility pass.
        family='fields' if kind in ('field','annotation_enum') else 'methods'
        return next((m for m in cls[family] if m['name']==name and m['descriptor']==d),None)
    def check_value(value, expected, cls, loc):
        tag=value['tag']; ok=False
        if expected.startswith('['):
            ok=tag=='['
            if ok:
                for idx,child in enumerate(value['values']): check_value(child,expected[1:],cls,loc+f'[{idx}]')
        elif expected in 'BCDFIJSZ' and len(expected)==1:
            ok=tag==expected and value['cp_tag']==({'D':6,'F':4,'J':5}.get(expected,3))
        elif expected=='Ljava/lang/String;': ok=tag=='s' and value['cp_tag']==1
        elif expected=='Ljava/lang/Class;': ok=tag=='c'
        elif expected.startswith('L'):
            if tag=='e': ok=value['type']==expected
            elif tag=='@': ok=value['annotation']['type']==expected
        if not ok: failure('annotation_element_value_type_mismatch',cls,location=loc,expected_descriptor=expected,value=value)
    for cls in all_classes:
        attributes.update(cls['attributes'])
        for ref in cls['type_refs']:
            ok=ref['target'] in new
            rec=dict(origin=cls['origin'],class_name=cls['name'],**ref,resolves=ok); occurrences.append(rec); ref_kinds[ref['kind']]+=1
            if not ok: failure('unresolved_type',cls,reference=ref)
        for ref in cls['member_refs']:
            member=resolve_member(ref['owner'],ref['name'],ref['descriptor'],ref['kind'])
            ok=member is not None
            if member:
                if not member['access'] & 0x0001 and cls['name'].rsplit('/',1)[0]!=ref['owner'].rsplit('/',1)[0]:
                    ok=False; failure('non_public_member_reference',cls,reference=ref)
                if ref['kind']=='annotation_enum' and not (member['access'] & 0x4000 and member['access'] & 0x0008):
                    ok=False; failure('invalid_enum_constant',cls,reference=ref)
            members.append(dict(origin=cls['origin'],class_name=cls['name'],**ref,resolves=ok))
            if not ok: failure('unresolved_member',cls,reference=ref)
        for ann in cls['annotations']:
            owner=ann['type'][1:-1]
            if not is_ep(owner): continue
            decl=new.get(owner); resolved=[]
            if not decl or not decl['access'] & 0x2000:
                failure('not_annotation_type',cls,annotation=ann); continue
            elems={m['name']:m for m in decl['methods'] if m['name'] not in ('<init>','<clinit>')}
            names=[e['name'] for e in ann['elements']]
            if len(names)!=len(set(names)): failure('duplicate_annotation_element',cls,annotation=ann)
            for val in ann['elements']:
                method=elems.get(val['name'])
                if not method or not method['descriptor'].startswith('()'):
                    failure('unresolved_annotation_element',cls,annotation=ann,element=val['name']); continue
                d=method['descriptor']; check_value(val['value'],d[2:],cls,ann['location']+':'+val['name'])
                resolved.append(dict(name=val['name'],descriptor=d,value=val['value']))
            omitted=[]
            for name,m in elems.items():
                if name not in names:
                    if 'default' not in m: failure('missing_required_annotation_element',cls,annotation=ann,element=name)
                    else: omitted.append(dict(name=name,descriptor=m['descriptor'],default=m['default']))
            annotations.append(dict(origin=cls['origin'],class_name=cls['name'],type=ann['type'],location=ann['location'],explicit_elements=resolved,omitted_defaulted_elements=omitted))
        literals.extend(dict(origin=cls['origin'],class_name=cls['name'],**v) for v in cls['literal_ep_strings'])
        # Independent conservative fallback checks ALL CP UTF8 names/descriptors for
        # an EP class, even if an attribute was not interpreted. This includes symbols
        # left over from compiler-generated metadata, plus reflection strings.
        for item in cls['raw_ep_utf8']:
            value=item['value']
            normalized=value.replace('com.google.errorprone.','com/google/errorprone/').replace('.', '/') if 'com.google.errorprone.' in value else value
            found=re.findall(r'com/google/errorprone/[A-Za-z0-9_$/]+',normalized)
            unknown=[s for s in found if s not in new and s not in ('com/google/errorprone/annotations','com/google/errorprone/annotations/concurrent')]
            if unknown:
                raw_residual.append(dict(origin=cls['origin'],class_name=cls['name'],**item,unresolved_names=unknown))
    if raw_residual: failures.extend(dict(kind='unresolved_raw_utf8_reference',**v) for v in raw_residual)
    proguard=[]
    for item in inputs:
        if item['scope']!='candidate': continue
        with zipfile.ZipFile(item['path']) as z:
            for name in z.namelist():
                if name.startswith('META-INF/proguard/') and not name.endswith('/'):
                    data=z.read(name)
                    proguard.append(dict(coordinate=item['coordinate'],jar_sha256=item['sha256'],resource=name,bytes=len(data),sha256=sha(data),text=data.decode('utf-8')))
    expected_proguard={('com.google.crypto.tink:tink-android:1.23.0','META-INF/proguard/protobuf.pro'),('com.google.code.gson:gson:2.13.2','META-INF/proguard/gson.pro')}
    if {(x['coordinate'],x['resource']) for x in proguard}!=expected_proguard: failures.append(dict(kind='unexpected_proguard_resources'))
    ep_by_artifact=collections.Counter(x['origin']['coordinate'] for x in annotations)
    annotation_types=collections.Counter(x['type'] for x in annotations)
    non_ep_annotations=[x for x in annotations if not is_ep(x['class_name'])]
    projected=[c for c in all_classes if c['origin']['coordinate']!=old_co and c['origin']['scope']!='project']
    duplicate_classes=collections.defaultdict(list)
    for c in projected:
        if c['name']=='module-info': continue
        duplicate_classes[c['name']].append(c['origin'])
    duplicates={k:v for k,v in duplicate_classes.items() if len(v)>1}
    if duplicates: failures.append(dict(kind='duplicate_projected_classes',duplicates=duplicates))
    summary=dict(status='PASS' if not failures else 'FAIL',negative_control=a.negative_control,baseline_commit=BASELINE,
                 candidate_manifest_sha256=CANDIDATE_MANIFEST_SHA256,candidate_import_map_sha256=sha(import_map_path.read_bytes()) if not a.bundle else None,
                 baseline_artifacts=len(baseline_rows),actual_project_artifacts=sum(i['scope']=='project' for i in inputs),actual_projects_matching_baseline_hash=sum(i.get('matches_baseline_hash',False) for i in inputs),candidate_artifacts=3,projected_runtime_artifacts=74,actual_runtime_inventories=actual_reports,actual_runtime_artifacts=83,
                 actual_graph_classfiles=sum(c['origin']['coordinate']!=old_co for c in all_classes),
                 actual_graph_ep_type_references=sum(x['origin']['coordinate']!=old_co for x in occurrences),
                 actual_graph_ep_member_references=sum(x['origin']['coordinate']!=old_co for x in members),
                 actual_graph_ep_annotation_occurrences=sum(x['origin']['coordinate']!=old_co for x in annotations),
                 actual_graph_explicit_annotation_elements=sum(len(x['explicit_elements']) for x in annotations if x['origin']['coordinate']!=old_co),
                 actual_graph_omitted_defaulted_annotation_elements=sum(len(x['omitted_defaulted_elements']) for x in annotations if x['origin']['coordinate']!=old_co),
                 scanned_classfiles=len(all_classes),projected_classfiles=len(projected),
                 projected_unique_non_module_classes=len(duplicate_classes),duplicate_projected_classes=duplicates,
                 old_ep_classes=len(old),replacement_ep_classes=len(new),old_classes_byte_identical=sum(x['byte_identical'] for x in compare),
                 old_classes_structurally_identical=sum(not x['changed_structural_properties'] for x in compare),
                 old_declared_fields=sum(len(c['fields']) for c in old.values()),old_declared_methods=sum(len(c['methods']) for c in old.values()),
                 old_public_protected_fields=sum(bool(m['access']&5) for c in old.values() for m in c['fields']),
                 old_public_protected_methods=sum(bool(m['access']&5) for c in old.values() for m in c['methods']),
                 ep_type_reference_occurrences=len(occurrences),ep_type_reference_kinds=dict(ref_kinds),
                 ep_member_reference_occurrences=len(members),ep_unique_member_references=len({(m['kind'],m['owner'],m['name'],m['descriptor']) for m in members}),
                 ep_annotation_occurrences=len(annotations),ep_annotations_outside_ep_classes=len(non_ep_annotations),
                 ep_explicit_annotation_element_occurrences=sum(len(x['explicit_elements']) for x in annotations),
                 ep_omitted_defaulted_annotation_element_occurrences=sum(len(x['omitted_defaulted_elements']) for x in annotations),
                 ep_annotation_types=dict(sorted(annotation_types.items())),ep_annotations_by_artifact=dict(sorted(ep_by_artifact.items())),
                 ep_literal_strings=literals,unresolved_utf8_references=raw_residual,all_classfile_attribute_counts=dict(sorted(attributes.items())),
                 proguard_resources=proguard,failures=failures,metadata_changes=notes,
                 gradle_executed=False,candidate_class_execution=False,device_or_apk_checks=False)
    def write(name,obj): (a.out/name).write_text(json.dumps(obj,indent=2,ensure_ascii=True,sort_keys=False)+'\n')
    write('summary.json',summary); write('verified-inputs.json',inventory); write('old-versus-replacement-api.json',compare)
    write('old-api.json',{k:canonical_api(v) for k,v in old.items()}); write('replacement-api.json',{k:canonical_api(v) for k,v in new.items()})
    write('type-reference-occurrences.json',occurrences); write('member-reference-occurrences.json',members); write('annotation-reference-occurrences.json',annotations)
    print(json.dumps({k:v for k,v in summary.items() if k not in ('all_classfile_attribute_counts','proguard_resources','ep_literal_strings')},indent=2))
    raise SystemExit(bool(failures))

if __name__=='__main__': main()
