import path from 'node:path';import {pathToFileURL} from 'node:url';const root='D:/CodexProjects/EasySaveVideo';const build=path.join(root,'.ui-ppt-build');const out=path.join(root,'docs/ui');const skill='C:/Users/benny/.codex/plugins/cache/openai-primary-runtime/presentations/26.1009.11318/skills/presentations';const font='Microsoft YaHei';const candidate=path.join(build,'candidate.pptx');const {finalizePresentation}=await import(pathToFileURL(path.join(skill,'container_tools/artifact_tool_utils.mjs')).href);
const final=path.join(out,'EasySaveVideo_UI_Effect_Design_V1.0_2026-10-11.pptx');
const result=await finalizePresentation({workspaceDir:root,candidatePath:candidate,finalPath:final,
 pythonExecutable:'C:/Users/benny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',
 integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),
 layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
 layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit',... [3,28,30,32,33].flatMap(n=>['--require-native-table-slide',String(n)])],
 requiredNativeTableOwnerSlides:[3,28,30,32,33],fontPolicy:{basis:'design',families:[font]},verifyArtifactToolImport:true,
 receiptPath:path.join(build,'validation-final.json')});
console.log(JSON.stringify({slides:34,final,result}));
