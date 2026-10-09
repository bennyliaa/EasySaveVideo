#!/usr/bin/env python3
"""Source identity only: bind the authorized media-close cherry-pick to reviewed bookmark bytes.

This does not execute or repeat media algorithms, replace native device QA, or imply runtime PASS.
"""
from pathlib import Path
import hashlib
import json
import stat
import subprocess
import sys

ORIGINAL = 'f78d7cc46c92bdd461e701ac3c39f62e12cb0ce5'
HOTFIX_SOURCE = '0c178320cd78391bfedd5f9846e3ee873f0f5fce'
BOOKMARK_CHECKPOINT = '53ee1863a5b60f44752d246e936b599ca1ca7628'
COMBINED_INTEGRATION = 'c3792eac3f98c87c4ea55331a271124d89e64829'
HOTFIX_PATCH_SHA256 = '11b053992a672fff53c59653008a6438a18bf133f3b23dc958e26a850b11238d'
BOOKMARK_INTEGRATION_PATCH_SHA256 = '28a12e6cc6d5f64fd5622265da74f95ea114a173ba4f9774c9bb429ae10e2d06'
APP = 'app/src/main/kotlin/com/easysavevideo/app/'
HOTFIX_FILES = (
    'app/build.gradle.kts',
    APP+'editor/storage/AndroidEditStorage.kt',
    APP+'recording/segments/AndroidRecordedSegmentStorage.kt',
    APP+'save/storage/AndroidSaveStorage.kt',
    APP+'storage/OwnedFileStreams.kt',
    'app/src/test/kotlin/com/easysavevideo/app/storage/OwnedFileStreamReleaseReviewTest.kt',
    'app/src/test/kotlin/com/easysavevideo/app/storage/OwnedFileStreamsTest.kt',
    'docs/media-close-hotfix/DIAGNOSIS_AND_VALIDATION.md',
    'docs/media-close-hotfix/evidence/TEST-com.easysavevideo.app.storage.OwnedFileStreamReleaseReviewTest.xml',
    'docs/media-close-hotfix/evidence/TEST-com.easysavevideo.app.storage.OwnedFileStreamsTest.xml',
    'docs/media-close-hotfix/evidence/frozen-close-reproduction.txt',
    'docs/media-close-hotfix/evidence/frozen-scope-check.json',
    'docs/media-close-hotfix/validation-summary.json',
    'scripts/check-library-admission.py',
)
INTEGRATIONS = (APP+'MainActivity.kt', APP+'browser/SystemBrowserHost.kt', APP+'ui/BrowserScreen.kt')
BOOKMARK_ADDITIONS = tuple(APP+'browser/bookmarks/'+name+'.kt' for name in (
    'AndroidBookmarkRuntime','AndroidBookmarkStorage','BookmarkCodec','BookmarkController','BookmarkPanel','BookmarkPolicy')) + tuple(
    'app/src/test/kotlin/com/easysavevideo/app/browser/bookmarks/'+name+'.kt' for name in (
    'BookmarkCodecTest','BookmarkControllerTest','BookmarkPolicyTest'))

def verify(repo):
    from scope_support import BASE as CURRENT_BASE, EDITS, verify_scope
    current_scope = verify_scope()
    def git(*args):
        return subprocess.check_output(['git', '-C', str(repo), *args])
    def files_changed(commit):
        return set(git('diff-tree','--no-commit-id','--name-only','-r',commit).decode().splitlines())
    def sha(data): return hashlib.sha256(data).hexdigest()
    def tree(commit,path):
        line=git('ls-tree',commit,'--',path).decode().strip()
        assert line, ('MISSING_REVIEWED_COMMIT_PATH',commit,path)
        metadata,name=line.split('\t'); mode,kind,oid=metadata.split()
        assert name==path and kind=='blob' and mode in ('100644','100755')
        return mode,oid,git('show',commit+':'+path)
    def match_live(commit,path):
        mode,oid,expected=tree(commit,path)
        file=repo/path; metadata=file.lstat()
        assert stat.S_ISREG(metadata.st_mode), ('NOT_REGULAR',path)
        assert bool(metadata.st_mode&0o111)==(mode=='100755'), ('MODE_CHANGED',path)
        actual=git('show',CURRENT_BASE+':'+path) if path in EDITS else file.read_bytes()
        assert actual==expected, ('REVIEWED_BASELINE_OR_UNCHANGED_BYTES_CHANGED',path)
        return {'path':path,'git_blob':oid,'sha256':sha(actual),'size':len(actual),'executable':mode=='100755'}
    assert git('rev-parse',HOTFIX_SOURCE+'^').decode().strip()==ORIGINAL
    assert git('rev-parse',BOOKMARK_CHECKPOINT+'^').decode().strip()==ORIGINAL
    assert git('rev-parse',COMBINED_INTEGRATION+'^').decode().strip()==BOOKMARK_CHECKPOINT
    assert files_changed(HOTFIX_SOURCE)==set(HOTFIX_FILES), 'HOTFIX_SOURCE_SCOPE_CHANGED'
    assert files_changed(COMBINED_INTEGRATION)==set(HOTFIX_FILES), 'CHERRY_PICK_SCOPE_CHANGED'
    source_patch=git('diff','--binary',ORIGINAL,HOTFIX_SOURCE)
    integrated_patch=git('diff','--binary',BOOKMARK_CHECKPOINT,COMBINED_INTEGRATION)
    assert source_patch==integrated_patch and sha(source_patch)==HOTFIX_PATCH_SHA256, 'HOTFIX_PATCH_NOT_IDENTICAL'
    hotfix=[]
    for path in HOTFIX_FILES:
        assert tree(HOTFIX_SOURCE,path)==tree(COMBINED_INTEGRATION,path), ('HOTFIX_TREE_MISMATCH',path)
        hotfix.append(match_live(HOTFIX_SOURCE,path))
    original_delta=git('diff','--binary',ORIGINAL,BOOKMARK_CHECKPOINT,'--',*INTEGRATIONS)
    combined_delta=git('diff','--binary',HOTFIX_SOURCE,COMBINED_INTEGRATION,'--',*INTEGRATIONS)
    assert original_delta==combined_delta and sha(original_delta)==BOOKMARK_INTEGRATION_PATCH_SHA256, 'BOOKMARK_INTEGRATION_PATCH_CHANGED'
    bookmarks=[match_live(BOOKMARK_CHECKPOINT,path) for path in (*INTEGRATIONS,*BOOKMARK_ADDITIONS)]
    existing_changes=set(git('diff','--name-only','--diff-filter=MTDR',HOTFIX_SOURCE,CURRENT_BASE).decode().splitlines())
    assert existing_changes==set(INTEGRATIONS), ('UNEXPECTED_HOTFIX_BASELINE_DELTA',sorted(existing_changes))
    return {'current_source_scope':current_scope, 'identity_adaptation':'Historical hotfix/bookmark identity compared to exact approved baseline; only explicit current edits are separately scope-checked and frozen', 'status':'PASS_BASELINE_AND_CURRENT_SCOPE_IDENTITY_ONLY','original_baseline':ORIGINAL,
            'hotfix_inclusive_baseline':HOTFIX_SOURCE,'bookmark_checkpoint':BOOKMARK_CHECKPOINT,
            'combined_integration':COMBINED_INTEGRATION,'hotfix_patch_sha256':sha(source_patch),
            'bookmark_integration_patch_sha256':sha(original_delta),'hotfix_files':hotfix,
            'unchanged_bookmark_files':bookmarks,'expected_combined_jvm_count':2035,
            'count_basis':'1959 hotfix-inclusive baseline + 76 bookmark tests; declared expectation, not a runtime result',
            'limits':['Source identity and integration-delta review only.','Hotfix device QA remains pending; no native or hotfix runtime PASS is claimed.','Original 2020-count bookmark evidence remains separate from combined verification.']}

if __name__=='__main__':
    root=Path(sys.argv[1]).resolve()
    print(json.dumps(verify(root),indent=2))
