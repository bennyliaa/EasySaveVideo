package com.easysavevideo.app.browser

/** Top-document, light-DOM element evidence. No URLs, media properties or credentials are read. */
internal object BrowserVideoPresenceScript {
    // SHOW_ALL counts even text/comment nodes. Never descend into frame documents or shadow roots.
    // The fixed node budget is not a promise that a hostile renderer can be forcibly interrupted.
    val SCRIPT = """
        (function() {
          'use strict';
          try {
            var walker = document.createTreeWalker(document, 0xFFFFFFFF);
            for (var i = 0; i < 512; i++) {
              var node = walker.nextNode();
              if (node === null) return false;
              if (node.nodeType === 1 && node.localName === 'video') return true;
            }
            return null;
          } catch (_) { return null; }
        })();
    """.trimIndent()
}
