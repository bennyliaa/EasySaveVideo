import fs from 'node:fs/promises';
import {PresentationFile,FileBlob} from 'file:///C:/Users/benny/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs';
const ref='D:/CodexProjectsFiles/LocalVideosBrowser/M3A-E/LocalVideosBrowser_M3A-E_UI_Framework_Document_Package_V1.0_2026-10-11/LocalVideosBrowser_UI_Effect_Design_V1.1_FINAL_2026-10-11.pptx';
const p=await PresentationFile.importPptx(await FileBlob.load(ref));
await fs.mkdir('reference',{recursive:true});
console.log((await p.inspect({kind:'layout',maxChars:3000})).ndjson);
for(let i=0;i<p.slides.items.length;i++){
 const png=await p.export({slide:p.slides.items[i],format:'png',scale:0.6});
 await fs.writeFile(`reference/${String(i+1).padStart(2,'0')}.png`,new Uint8Array(await png.arrayBuffer()));
}
console.log('reference slides '+p.slides.items.length);
