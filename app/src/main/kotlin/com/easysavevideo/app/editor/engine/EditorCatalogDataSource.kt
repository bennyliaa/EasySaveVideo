package com.easysavevideo.app.editor.engine

/** Existing private per-item addresses; routing/IO lives in the shared capability data source. */
internal object EditorCatalogDataSource {
    fun address(index: Int): String = "esv-edit:///source-$index.mp4"
}
