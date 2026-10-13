"""Read production-derived gate, alter only in-memory test doubles; never edit input files."""
import importlib.util,io,json,unittest
from pathlib import Path
source=Path(__file__).resolve().parents[3] / 'scripts/verify-current-storage-sqlite.py'
cases=[
 ('omit_item_immutability','test_batch_and_item_identity_replay_history_and_result_are_immutable','library_directory_item_immutable'),
 ('omit_stopped_start_guard','test_stopped_and_finished_batches_cannot_start_or_resume_and_completed_item_cannot_start','library_directory_item_start_guard'),
 ('omit_retained_item_guard','test_batch_and_item_identity_replay_history_and_result_are_immutable','library_directory_item_retained'),
 ('omit_active_grant_guard','test_batch_admission_requires_existing_active_grant_and_never_changes_default','library_directory_batch_active_grant'),
 ('omit_foreign_key_validation','test_corrupt_foreign_key_is_not_silently_repaired_or_version_advanced',None),
]
results=[]
for name,test,trigger in cases:
 spec=importlib.util.spec_from_file_location('gate_'+name,source);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 if trigger:
  old=len(module.BATCH_DDL);module.BATCH_DDL=[s for s in module.BATCH_DDL if not s.startswith('CREATE TRIGGER '+trigger+' ')]
  assert len(module.BATCH_DDL)==old-1
 else: module.foreign_keys_clean=lambda db:None
 capture=io.StringIO(); result=unittest.TextTestRunner(stream=capture).run(unittest.TestSuite([module.CurrentStorageContracts(test)]))
 # A crash is not a successful behavioral detection: require an assertion failure, no error.
 detected=len(result.failures)>0 and len(result.errors)==0
 results.append({'mutation':name,'test':test,'detected_by_assertion':detected,'failures':len(result.failures),'errors':len(result.errors)})
 assert detected,capture.getvalue()
print(json.dumps({'scope':'in-memory mutations of host contract model only; production inputs unchanged','results':results},indent=2))
