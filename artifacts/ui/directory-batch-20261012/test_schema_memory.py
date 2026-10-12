from pathlib import Path
import sqlite3,re,json
r=Path(__file__).resolve().parents[3]
out=r/'artifacts/ui/directory-batch-20261012';out.mkdir(parents=True,exist_ok=True)
source=(r/'app/src/main/kotlin/com/easysavevideo/app/library/export/DirectoryBatchSchema.kt').read_text(encoding='utf-8')
body=source.split('val statements=listOf(',1)[1].split('\n    )',1)[0]
statements=[a or b for a,b in re.findall(r'"""(.*?)"""|"([^"\n]*)"',body,re.S)]
assert len(statements)==9
def fresh():
    db=sqlite3.connect(':memory:');db.execute('PRAGMA foreign_keys=ON')
    db.execute('CREATE TABLE ordinary_folder_grant(folder_grant_id TEXT PRIMARY KEY,retired INTEGER NOT NULL,invalidated INTEGER NOT NULL)')
    db.execute('CREATE TABLE library_export(operation_id TEXT PRIMARY KEY,phase TEXT NOT NULL)')
    db.execute('CREATE TABLE synthetic_legacy_rows(id TEXT PRIMARY KEY,value BLOB)')
    db.execute('INSERT INTO synthetic_legacy_rows VALUES(?,?)',('sentinel',b'synthetic-preserved'))
    db.execute("INSERT INTO ordinary_folder_grant VALUES('chosen',0,0)");db.execute("INSERT INTO ordinary_folder_grant VALUES('different',0,0)")
    db.execute('PRAGMA user_version=7');db.commit()
    return db
passed=[]
def expect_rejected(db,sql,args=()):
    try:db.execute(sql,args)
    except sqlite3.IntegrityError:return
    raise AssertionError('Unexpected mutation accepted')
db=fresh();db.execute('BEGIN')
for sql in statements:db.execute(sql)
db.execute('PRAGMA user_version=8');db.commit()
assert db.execute('SELECT value FROM synthetic_legacy_rows').fetchone()[0]==b'synthetic-preserved'
assert db.execute('PRAGMA user_version').fetchone()[0]==8
assert db.execute('PRAGMA foreign_key_check').fetchall()==[]
passed.append('additive_v7_v8_preserves_synthetic_legacy_and_foreign_keys')
rollback=fresh();rollback.execute('BEGIN')
for sql in statements:rollback.execute(sql)
rollback.execute('PRAGMA user_version=8');rollback.rollback()
assert rollback.execute('PRAGMA user_version').fetchone()[0]==7
assert not rollback.execute("SELECT name FROM sqlite_master WHERE name='library_directory_batch'").fetchone()
assert rollback.execute('SELECT value FROM synthetic_legacy_rows').fetchone()[0]==b'synthetic-preserved'
passed.append('failure_rolls_back_all_ddl_and_version_no_drop')
for grant,retired,invalidated in [('retired',1,0),('lost',0,1)]:
    db.execute('INSERT INTO ordinary_folder_grant VALUES(?,?,?)',(grant,retired,invalidated))
    expect_rejected(db,'INSERT INTO library_directory_batch VALUES(?,?,?)',(grant,grant,'ADMITTED'))
expect_rejected(db,"INSERT INTO library_directory_batch VALUES('missing','missing','ADMITTED')")
passed.append('missing_retired_invalidated_grants_cannot_admit')
db.execute("INSERT INTO library_directory_batch VALUES('batch','chosen','ADMITTED')")
db.execute("INSERT INTO library_directory_item VALUES('op','batch',0,'source',1,1,'output-grant',0,NULL)")
journal=(r/'app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidDirectoryBatchJournal.kt').read_text(encoding='utf-8')
folder_query=re.search(r'fun folderForOperation.*?rawQuery\("(SELECT b.folder_grant_id.*?)",arrayOf\(operationId\)\)',journal,re.S).group(1)
db.execute('CREATE TABLE synthetic_default_directory(selected TEXT)')
db.execute("INSERT INTO synthetic_default_directory VALUES('different')")
assert db.execute(folder_query,('op',)).fetchone()[0]=='chosen'
db.execute("UPDATE synthetic_default_directory SET selected='another-default'")
assert db.execute(folder_query,('op',)).fetchone()[0]=='chosen'
db.execute("UPDATE ordinary_folder_grant SET retired=1 WHERE folder_grant_id='chosen'")
assert db.execute(folder_query,('op',)).fetchone()[0]=='chosen'
passed.append('operation_lookup_frozen_despite_default_change_and_retirement')
db.execute("UPDATE ordinary_folder_grant SET retired=0 WHERE folder_grant_id='chosen'")
expect_rejected(db,"UPDATE library_directory_batch SET folder_grant_id='different' WHERE batch_id='batch'")
for column,value in [('operation_id','another'),('batch_id','another'),('ordinal',1),('item_id','another'),('revision',2),('generation',2),('grant_id','another')]:
    expect_rejected(db,f'UPDATE library_directory_item SET {column}=? WHERE operation_id=?',(value,'op'))
passed.append('immutable_target_source_order_and_operation_id')
expect_rejected(db,"INSERT INTO library_directory_item VALUES('op2','batch',0,'other',1,1,'g2',0,NULL)")
expect_rejected(db,"INSERT INTO library_directory_item VALUES('op2','batch',1,'source',1,1,'g2',0,NULL)")
expect_rejected(db,"INSERT INTO library_directory_item VALUES('op2','batch',100,'other',1,1,'g2',0,NULL)")
passed.append('duplicate_order_source_and_overbudget_item_rejected')
db.execute("UPDATE library_directory_item SET started=1 WHERE operation_id='op'")
expect_rejected(db,"UPDATE library_directory_item SET started=0 WHERE operation_id='op'")
db.execute("UPDATE library_directory_item SET outcome='VERIFIED_COPY' WHERE operation_id='op'")
expect_rejected(db,"UPDATE library_directory_item SET outcome=NULL WHERE operation_id='op'")
expect_rejected(db,"UPDATE library_directory_item SET outcome='FAILED_UNCONFIRMED' WHERE operation_id='op'")
passed.append('start_and_known_outcome_never_rewind')
db.execute("INSERT INTO library_directory_item VALUES('waiting','batch',1,'other',1,1,'g2',0,NULL)")
db.execute("UPDATE library_directory_batch SET phase='STOPPED' WHERE batch_id='batch'")
expect_rejected(db,"UPDATE library_directory_item SET started=1 WHERE operation_id='waiting'")
expect_rejected(db,"UPDATE library_directory_batch SET phase='ADMITTED' WHERE batch_id='batch'")
expect_rejected(db,"UPDATE library_directory_batch SET phase='FINISHED' WHERE batch_id='batch'")
passed.append('stopped_batch_cannot_resurrect_or_start_waiting_item')
expect_rejected(db,"DELETE FROM library_directory_item WHERE operation_id='op'")
expect_rejected(db,"DELETE FROM library_directory_batch WHERE batch_id='batch'")
passed.append('idempotency_history_retained')
db.execute('CREATE TABLE library_export_target(operation_id TEXT PRIMARY KEY,uri TEXT)')
db.execute("INSERT INTO library_export_target VALUES('older-owner','content://synthetic/owned')")
export_journal=(r/'app/src/main/kotlin/com/easysavevideo/app/library/export/AndroidExportJournal.kt').read_text(encoding='utf-8')
collision_query=re.search(r'rawQuery\("(SELECT 1 FROM library_export_target WHERE uri=\? AND operation_id<>\? LIMIT 1)"',export_journal).group(1)
assert db.execute(collision_query,('content://synthetic/owned','new-owner')).fetchone()
assert not db.execute(collision_query,('content://synthetic/owned','older-owner')).fetchone()
assert not db.execute(collision_query,('content://synthetic/fresh','new-owner')).fetchone()
passed.append('known_output_uri_cannot_rebind_to_another_operation')
db.execute("INSERT INTO library_directory_batch VALUES('finished','chosen','ADMITTED')")
db.execute("INSERT INTO library_directory_item VALUES('uncertain','finished',0,'third',1,1,'g3',1,'FAILED_UNCONFIRMED')")
db.execute("UPDATE library_directory_batch SET phase='FINISHED' WHERE batch_id='finished'")
db.execute("INSERT INTO library_export VALUES('uncertain','PREPARED')")
update=re.search(r'db.execSQL\("(UPDATE library_directory_batch SET phase=\'STOPPED\' WHERE phase=\'FINISHED\'.*?)"\)',journal).group(1)
db.execute(update)
query=re.search(r'val ids=db.rawQuery\("(SELECT i.operation_id FROM library_directory_item i JOIN library_export.*?)",null\)',journal).group(1)
assert db.execute(query).fetchall()==[('uncertain',)]
assert db.execute("SELECT phase FROM library_directory_batch WHERE batch_id='finished'").fetchone()[0]=='STOPPED'
passed.append('finished_attempt_with_prepared_export_is_fenced_before_recovery')
storage=(r/'app/src/main/kotlin/com/easysavevideo/app/save/storage/AndroidSaveStorage.kt').read_text(encoding='utf-8')
assert 'if(oldVersion<7)FolderDestinationSchema.migrate(db)' in storage
assert 'if (oldVersion !in 1..7 || newVersion != 8)' in storage
assert storage.count('DirectoryBatchSchema.create(db)')==2
passed.append('android_upgrade_and_fresh_create_have_explicit_additive_v8_routes')
result={'status':'PASS','groups':len(passed),'passed':passed,'sqlite_version':sqlite3.sqlite_version,'scope':'DESKTOP_SQLITE_IN_MEMORY_EXACT_KOTLIN_SCHEMA_AND_RECOVERY_SQL_ONLY','Android_SQLite_migration':'NOT_RUN','real_directories_or_phone_data':'NOT_TOUCHED','legacy_rows':'SYNTHETIC_ONLY'}
(out/'SQLITE-MEMORY-RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(result))
