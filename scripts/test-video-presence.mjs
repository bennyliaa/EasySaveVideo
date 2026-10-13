#!/usr/bin/env node
// Owned synthetic light DOM; not real-site or Android/WebView acceptance.
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const source = readFileSync('app/src/main/kotlin/com/easysavevideo/app/browser/BrowserVideoPresenceScript.kt', 'utf8');
const script = source.split('val SCRIPT = """')[1].split('""".trimIndent()')[0];
let checks = 0;
const forbidden = () => { throw new Error('FORBIDDEN_ACCESS'); };
function node(name, extra = {}) {
  const value = { nodeType: 1, localName: name, ...extra };
  for (const key of ['src', 'currentSrc', 'duration', 'videoWidth', 'mediaKeys', 'textContent', 'contentDocument', 'shadowRoot']) {
    Object.defineProperty(value, key, { get: forbidden });
  }
  return value;
}
function run(nodes, expected, fail = false) {
  let visits = 0;
  const document = { createTreeWalker(root, mask) {
    assert.equal(root, document); assert.equal(mask, 0xFFFFFFFF);
    if (fail) throw new Error('SYNTHETIC_DOM_ERROR');
    let index = 0;
    return { nextNode() { visits++; return nodes[index++] ?? null; } };
  }};
  Object.defineProperty(document, 'cookie', { get: forbidden });
  const context = { document, fetch: forbidden, XMLHttpRequest: forbidden, MutationObserver: forbidden,
    setTimeout: forbidden, setInterval: forbidden, requestAnimationFrame: forbidden };
  Object.defineProperty(context, 'location', { get: forbidden });
  assert.equal(vm.runInNewContext(script, context, { timeout: 100 }), expected);
  assert.ok(visits <= 512); checks++; return visits;
}
run([], false);
run([node('video')], true);
run([node('div'), node('video')], true);
run([node('iframe')], false); // No contentDocument access; frame contents are not walked.
run([node('custom-player')], false); // No shadowRoot access or inference from player-like tag names.
run([{ nodeType: 3, localName: 'video' }], false);
run(Array.from({length: 511}, () => node('div')).concat(node('video')), true);
run(Array.from({length: 512}, () => node('div')).concat(node('video')), null);
assert.equal(run(Array.from({length: 10000}, () => node('div')), null), 512);
run([], null, true);
run([{ get nodeType() { throw new Error('BAD_NODE'); } }], null);
for (const term of ['currentSrc', '.src', 'cookie', 'location', 'contentDocument', 'shadowRoot', 'fetch(', 'setInterval(', 'setTimeout(', 'MutationObserver', 'addJavascriptInterface']) {
  assert.ok(!script.includes(term)); checks++;
}
console.log(JSON.stringify({status:'PASS', checks, scope:'production script in owned synthetic DOM only', androidWebView:'NOT_RUN', realSite:'NOT_RUN'}));
