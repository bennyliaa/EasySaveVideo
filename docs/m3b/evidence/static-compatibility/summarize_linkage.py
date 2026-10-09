from pathlib import Path
import json,collections
B=Path(__file__).resolve().parent;d=json.load(open(B/'ABI_REPORT.json'));old=json.load(open(B/'old-class-symbols.json'));new=json.load(open(B/'new-class-symbols.json'));ret={v['coordinate']for v in old.values()}&{v['coordinate']for v in new.values()}
def lineage(graph,name,seen=None):
 seen=set()if seen is None else seen
 if name in seen:return set()
 seen.add(name);c=graph.get(name);out={name}
 if c:
  for p in [c['super']]+c['interfaces']:
   if p:out|=lineage(graph,p,seen)
 return out
summary=[];refs=[];removed=[]
for p in d['pairs']:
 targets={r['owner']for r in p['new_abstract_methods_on_retained_public_classes']}
 implementers=[]
 for n,c in old.items():
  if c['coordinate']in ret:
   match=lineage(old,n)&targets
   if match:implementers.append({'coordinate':c['coordinate'],'class':n,'affected_ancestors':sorted(match)})
 counts=collections.Counter(r['baseline_coordinate']for r in p['retained_baseline_references'])
 changed_class_types={x['class']for x in p['abi_flag_or_super_changes']if x['change']=='class access/final/interface/abstract'and (x['old_access']^x['new_access'])&(1|0x200)}
 changed_class_type_refs=[{'coordinate':c['coordinate'],'referencing_class':n,'affected_type':t}for n,c in old.items()if c['coordinate']in ret for t in set(c['type_references'])&changed_class_types]
 summary.append({'old_coordinate':p['old_coordinate'],'new_coordinate':p['new_coordinate'],'old_public_classes_missing':len(p['public_classes_absent_in_new_graph']),'removed_or_access_narrowed_public_protected_jvm_symbols':len(p['old_public_protected_symbols_unresolved_in_new_graph']),'removed_symbols_at_retained_owners':sum(not r.get('owner_class_absent')for r in p['old_public_protected_symbols_unresolved_in_new_graph']),'member_refs_in_retained_baseline':len(p['retained_baseline_references']),'member_ref_linkage_failures':len(p['retained_baseline_unresolved_or_flag_problem_references']),'removed_type_references_in_retained_baseline':p['retained_baseline_removed_type_references'],'old_baseline_implementers_of_changed_abstract_api':implementers,'ref_counts_by_baseline_coordinate':dict(sorted(counts.items())),'generic_signature_change_count':len(p['generic_signature_changes']),'retained_baseline_references_to_visibility_or_kind_changed_types':changed_class_type_refs})
 for r in p['retained_baseline_references']:refs.append([p['old_coordinate'],r['baseline_coordinate'],r['referencing_class'],r['kind'],r['owner'],r['name'],r['descriptor'],r['status'],r['after_resolution']['owner']if r['after_resolution']else''])
 for r in p['old_public_protected_symbols_unresolved_in_new_graph']:removed.append([p['old_coordinate'],r['kind'],r['owner'],r['name'],r['descriptor'],'class_absent'if r.get('owner_class_absent')else 'missing_or_access_narrowed'])
result={'retained_baseline_artifacts':len({r['coordinate']for r in d['old_artifacts']}&{r['coordinate']for r in d['new_artifacts']}),'retained_baseline_artifacts_with_runtime_classes':len(ret),'static_method_member_analysis':'actual classfile constant-pool references, not source-text guesses and not runtime coverage','pairs':summary,'all_retained_baseline_member_references_resolve':all(not p['member_ref_linkage_failures']for p in summary),'no_retained_baseline_removed_type_references':all(not p['removed_type_references_in_retained_baseline']for p in summary),'no_old_baseline_changed_abstract_api_implementers':all(not p['old_baseline_implementers_of_changed_abstract_api']for p in summary),'does_not_establish_universal_coroutines_binary_compatibility':True,'runtime_behavior_verified_by_this_audit':False}
(B/'LINKAGE_REFERENCE_SUMMARY.json').write_text(json.dumps(result,indent=2)+'\n')
(B/'REMOVED_JVM_SYMBOLS.tsv').write_text('old_coordinate\tkind\towner\tname\tdescriptor\tchange\n'+'\n'.join('\t'.join(r)for r in removed)+'\n')
(B/'BASELINE_MEMBER_REFERENCES.tsv').write_text('old_target_coordinate\tbaseline_coordinate\treferencing_class\tkind\towner\tname\tdescriptor\tstatus\tnew_declaring_owner\n'+'\n'.join('\t'.join(r)for r in refs)+'\n')
print(json.dumps(result,indent=2))
