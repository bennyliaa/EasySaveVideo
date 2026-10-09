#!/usr/bin/env node
// Synthetic DOM only. This is not Android/WebView or a real website acceptance test.
import { readFileSync, writeFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const source = readFileSync('app/src/main/kotlin/com/easysavevideo/app/save/PageSaveDetector.kt', 'utf8');
const script = source.split('private val SCRIPT = """')[1].split('""".trimIndent()')[0];
let total = 0;
function run(videos) {
  let requests = 0;
  const context = { document: { getElementsByTagName(name) { assert.equal(name, 'video'); return videos; } },
    location: { href: 'https://sample.invalid/watch' },
    fetch() { requests++; throw new Error('UNEXPECTED_NETWORK'); }, setInterval() { throw new Error('UNEXPECTED_TIMER'); } };
  const result = JSON.parse(vm.runInNewContext(script, context, { timeout: 100 }));
  assert.equal(requests, 0); assert.equal(result.page, context.location.href); total++; return result;
}
function video(url, children = []) { return { currentSrc: url, src: url, type: 'video/mp4', videoWidth: 640, videoHeight: 360,
  duration: 10, webkitAudioDecodedByteCount: 1, mediaKeys: null, getElementsByTagName(name) { assert.equal(name, 'source'); return children; } }; }
assert.equal(run([]).items.length, 0);
assert.equal(run([video('https://sample.invalid/one.mp4')]).items[0].audio, true);
const protectedVideo = video('https://sample.invalid/encrypted.mp4'); protectedVideo.mediaKeys = {};
assert.equal(run([protectedVideo]).items[0].protected, true);
assert.equal(run([video('blob:https://sample.invalid/id')]).unsupported, true);
assert.equal(run([video('http://sample.invalid/one.mp4')]).items.length, 0);
assert.equal(run([video('file:///private/media.mp4')]).items.length, 0);
assert.equal(run(Array.from({ length: 10000 }, (_, i) => video(`https://sample.invalid/${i}.mp4`))).items.length, 32);
assert.equal(run([video('https://sample.invalid/' + 'a'.repeat(8193))]).items.length, 0);
const variants = video('', [{src:'https://sample.invalid/low.mp4', type:'video/mp4'}, {src:'https://sample.invalid/high.mp4', type:'video/mp4'}]);
assert.equal(run([variants]).items.length, 2);
const nonfinite = video('https://sample.invalid/live.mp4'); nonfinite.duration = Infinity;
assert.equal(run([nonfinite]).items[0].duration, null);
assert.ok(!script.includes('addJavascriptInterface') && !script.includes('fetch(') && !script.includes('setInterval(')); total++;
const report = { status: 'PASS', checks: total, scope: 'synthetic bounded DOM script only', androidWebView: 'NOT_RUN', realSite: 'NOT_RUN' };
writeFileSync('docs/m3a/evidence/detector-script-tests.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report));
