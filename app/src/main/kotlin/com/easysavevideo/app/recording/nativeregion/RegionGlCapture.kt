package com.easysavevideo.app.recording.nativeregion

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.easysavevideo.recording.RegionCaptureRecipe
import com.easysavevideo.recording.RecordingError
import com.easysavevideo.recording.RecordingFailure
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Original, single-owner EGL crop adapter. The full display exists only in the transient OES
 * source; the encoder receives exactly the selected pixels. M6-C optionally reads one bounded
 * full-authorized-frame preview to RAM before any encoder exists. No preview file is ever made.
 * GPU/driver behavior is a separately unrun device gate, not established by JVM tests.
 */
internal class RegionGlCapture(private var recipe: RegionCaptureRecipe, frameRate: Int) {
    private val frameAdmission = RegionFrameAdmission(frameRate)
    private val owner = Thread.currentThread()
    private val framePending = AtomicBoolean()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var parking: EGLSurface = EGL14.EGL_NO_SURFACE
    private var output: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewOutput: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewRead = false
    private var texture = 0
    private var program = 0
    private var sourceTexture: SurfaceTexture? = null
    private var source: Surface? = null
    private var positionLocation = -1
    private var transformLocation = -1
    private var cropLocation = -1
    private var samplerLocation = -1
    private val transform = FloatArray(16)
    private var lastTimestampNs = -1L
    private var released = false
    private var releaseUncertain = false
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
    val sourceSurface: Surface get() = requireNotNull(source)

    fun initialize(encoderSurface: Surface? = null) {
        assertOwner()
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        requireNative(display != EGL14.EGL_NO_DISPLAY)
        val versions = IntArray(2)
        requireNative(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: ""
        requireNative(extensions.split(' ').contains("EGL_ANDROID_presentation_time"))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        requireNative(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] == 1)
        config = configs[0] ?: fail()
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        requireNative(context != EGL14.EGL_NO_CONTEXT)
        parking = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        requireNative(parking != EGL14.EGL_NO_SURFACE)
        current(parking)
        val maxTexture = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
        requireNative(maxOf(recipe.source.width, recipe.source.height) <= maxTexture[0])
        val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        requireNative(glExtensions.split(' ').contains("GL_OES_EGL_image_external"))
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); texture = ids[0]
        requireNative(texture != 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = createProgram()
        positionLocation = GLES20.glGetAttribLocation(program, "position")
        transformLocation = GLES20.glGetUniformLocation(program, "sourceTransform")
        cropLocation = GLES20.glGetUniformLocation(program, "crop")
        samplerLocation = GLES20.glGetUniformLocation(program, "sourceImage")
        requireNative(listOf(positionLocation, transformLocation, cropLocation, samplerLocation).all { it >= 0 })
        checkGl()
        sourceTexture = SurfaceTexture(texture).also {
            it.setDefaultBufferSize(recipe.source.width, recipe.source.height)
            // Callback does not touch GL and cannot enqueue an unbounded frame history.
            it.setOnFrameAvailableListener({ framePending.set(true) }, Handler(Looper.getMainLooper()))
        }
        source = Surface(requireNotNull(sourceTexture))
        encoderSurface?.let(::setEncoderSurface)
    }

    fun setEncoderSurface(encoderSurface: Surface) {
        assertOwner(); check(output == EGL14.EGL_NO_SURFACE)
        output = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        requireNative(output != EGL14.EGL_NO_SURFACE)
        val size = IntArray(1)
        requireNative(EGL14.eglQuerySurface(display, output, EGL14.EGL_WIDTH, size, 0) && size[0] == recipe.pixelCrop.width)
        requireNative(EGL14.eglQuerySurface(display, output, EGL14.EGL_HEIGHT, size, 0) && size[0] == recipe.pixelCrop.height)
        current(parking)
    }

    /** Must finish before the codec-owned Surface is released. Source and context remain alive. */
    fun clearEncoderSurface() {
        assertOwner()
        if (output == EGL14.EGL_NO_SURFACE) return
        current(parking)
        GLES20.glFinish(); checkGl()
        requireNative(EGL14.eglDestroySurface(display, output))
        output = EGL14.EGL_NO_SURFACE
    }

    /** Change only the selected region. The original projection/source/context remains owned. */
    fun selectRecipe(selected: RegionCaptureRecipe) {
        assertOwner()
        check(output == EGL14.EGL_NO_SURFACE && selected.source == recipe.source &&
            selected.scopeEvidence == recipe.scopeEvidence)
        recipe = selected
    }

    /** Latch/discard while paused or awaiting source attestation. Never synthesize CFR frames. */
    fun pump(encode: Boolean, earliestFrameNs: Long, stillAllowed: (FloatArray) -> Boolean): Boolean {
        assertOwner()
        if (!latch()) return false
        // Revalidate even while not encoding, including when waiting on preview UI retirement.
        if (!stillAllowed(transform) || !encode || lastTimestampNs < earliestFrameNs) return true
        validateTimestamp()
        if (!frameAdmission.admit(lastTimestampNs)) return true
        requireNative(output != EGL14.EGL_NO_SURFACE)
        current(output)
        val crop = recipe.pixelCrop
        draw(crop.width, crop.height, RegionTexturePolicy.textureEdges(crop, recipe.source.width, recipe.source.height))
        if (!stillAllowed(transform)) { current(parking); return true }
        requireNative(EGLExt.eglPresentationTimeANDROID(display, output, lastTimestampNs))
        requireNative(EGL14.eglSwapBuffers(display, output))
        current(parking)
        return true
    }

    /** Admit only a newly latched post-retirement frame, without encoding or another readback. */
    fun observeFrame(earliestFrameNs: Long, stillAllowed: (FloatArray) -> Boolean): Boolean {
        assertOwner()
        if (!latch() || !stillAllowed(transform) || lastTimestampNs < earliestFrameNs) return false
        validateTimestamp()
        return true
    }

    /** One original OES shader draw/readback, entirely on this GL owner, never on the main thread. */
    fun readPreview(earliestFrameNs: Long, stillAllowed: (FloatArray) -> Boolean): PreviewReadback? {
        assertOwner()
        check(Looper.myLooper() != Looper.getMainLooper() && !previewRead && output == EGL14.EGL_NO_SURFACE)
        if (!latch()) return null
        if (!stillAllowed(transform) || lastTimestampNs < earliestFrameNs) return null
        validateTimestamp()
        // From here, no retry/replacement snapshot may allocate another raster for this session.
        previewRead = true
        val size = RegionPreviewRasterPolicy.size(recipe.source.width, recipe.source.height)
        previewOutput = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, size.width, EGL14.EGL_HEIGHT, size.height, EGL14.EGL_NONE), 0)
        requireNative(previewOutput != EGL14.EGL_NO_SURFACE)
        var bytes: ByteBuffer? = null
        var colors: IntArray? = null
        var bitmap: Bitmap? = null
        try {
            current(previewOutput)
            draw(size.width, size.height, floatArrayOf(0f, 0f, 1f, 1f))
            bytes = ByteBuffer.allocateDirect(size.pixels * 4)
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
            GLES20.glReadPixels(0, 0, size.width, size.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            checkGl()
            if (!stillAllowed(transform)) throw RecordingFailure(RecordingError.FORMAT_CHANGED)
            colors = RegionPreviewRasterPolicy.topDownArgb(bytes, size)
            bitmap = Bitmap.createBitmap(colors, size.width, size.height, Bitmap.Config.ARGB_8888)
            requireNative(!bitmap.isMutable)
            if (!stillAllowed(transform)) throw RecordingFailure(RecordingError.FORMAT_CHANGED)
            val result = PreviewReadback(bitmap, lastTimestampNs, transform.copyOf())
            // Destroy the temporary GPU render target before transferring the only CPU bitmap.
            clearPreviewSurface()
            // Readback/finish can block after the initial freshness check. Never publish a frame
            // whose source timestamp aged outside the accepted window during those native calls.
            validateTimestamp()
            bitmap = null
            return result
        } catch (_: OutOfMemoryError) {
            throw RecordingFailure(RecordingError.BUFFER_LIMIT)
        } finally {
            colors?.fill(0)
            bytes?.let { buffer -> buffer.clear(); while (buffer.hasRemaining()) buffer.put(0.toByte()) }
            // This bitmap was never published, so no UI/render-thread references exist.
            bitmap?.recycle()
            clearPreviewSurface()
        }
    }

    private fun latch(): Boolean {
        if (!framePending.getAndSet(false)) return false
        current(parking)
        val image = requireNotNull(sourceTexture)
        image.updateTexImage()
        val timestamp = image.timestamp
        if (timestamp == lastTimestampNs) return false
        if (timestamp <= 0 || timestamp < lastTimestampNs) throw RecordingFailure(RecordingError.TIMESTAMP_INVALID)
        lastTimestampNs = timestamp
        image.getTransformMatrix(transform)
        if (!RegionTexturePolicy.isUnscaledFullImage(transform)) throw RecordingFailure(RecordingError.PROFILE_UNSUPPORTED)
        return true
    }

    private fun validateTimestamp() {
        val now = System.nanoTime()
        if (lastTimestampNs > now || now - lastTimestampNs > 5_000_000_000L)
            throw RecordingFailure(RecordingError.TIMESTAMP_INVALID)
    }

    private fun draw(width: Int, height: Int, edges: FloatArray) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glUniformMatrix4fv(transformLocation, 1, false, transform, 0)
        GLES20.glUniform4fv(cropLocation, 1, edges, 0)
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        checkGl()
    }

    private fun clearPreviewSurface() {
        if (previewOutput == EGL14.EGL_NO_SURFACE) return
        try {
            current(parking)
            GLES20.glFinish(); checkGl()
            requireNative(EGL14.eglDestroySurface(display, previewOutput))
            previewOutput = EGL14.EGL_NO_SURFACE
        } catch (error: RuntimeException) {
            // A later successful cleanup attempt never erases an uncertain native release.
            releaseUncertain = true
            throw error
        }
    }

    class PreviewReadback(val bitmap: Bitmap, val timestampNs: Long, val transform: FloatArray)

    /** Best-effort all resources; any uncertainty permanently fences the enclosing capture lane. */
    fun release(): Boolean {
        assertOwner()
        if (released) return true
        var confirmed = !releaseUncertain
        fun releasePart(action: () -> Unit) { try { action() } catch (_: Exception) { confirmed = false; releaseUncertain = true } }
        releasePart { clearEncoderSurface() }
        releasePart { clearPreviewSurface() }
        releasePart { source?.release(); source = null }
        releasePart { sourceTexture?.setOnFrameAvailableListener(null); sourceTexture?.release(); sourceTexture = null }
        if (context != EGL14.EGL_NO_CONTEXT && parking != EGL14.EGL_NO_SURFACE) {
            releasePart {
                current(parking)
                if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
                if (texture != 0) { GLES20.glDeleteTextures(1, intArrayOf(texture), 0); texture = 0 }
                GLES20.glFinish(); checkGl()
            }
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            releasePart { requireNative(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) }
            if (parking != EGL14.EGL_NO_SURFACE) releasePart { requireNative(EGL14.eglDestroySurface(display, parking)); parking = EGL14.EGL_NO_SURFACE }
            if (context != EGL14.EGL_NO_CONTEXT) releasePart { requireNative(EGL14.eglDestroyContext(display, context)); context = EGL14.EGL_NO_CONTEXT }
            releasePart { requireNative(EGL14.eglTerminate(display)); display = EGL14.EGL_NO_DISPLAY }
            releasePart { requireNative(EGL14.eglReleaseThread()) }
        }
        released = confirmed
        return confirmed
    }

    private fun createProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 position;
            uniform mat4 sourceTransform;
            uniform vec4 crop;
            varying highp vec2 uv;
            void main() {
                vec2 p = position * 0.5 + 0.5;
                vec2 selected = mix(crop.xy, crop.zw, p);
                uv = (sourceTransform * vec4(selected, 0.0, 1.0)).xy;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """.trimIndent())
        var fragment = 0
        var linked = 0
        try {
            fragment = compile(GLES20.GL_FRAGMENT_SHADER, """
                #extension GL_OES_EGL_image_external : require
                precision highp float;
                uniform samplerExternalOES sourceImage;
                varying highp vec2 uv;
                void main() { gl_FragColor = texture2D(sourceImage, uv); }
            """.trimIndent())
            linked = GLES20.glCreateProgram(); requireNative(linked != 0)
            GLES20.glAttachShader(linked, vertex); GLES20.glAttachShader(linked, fragment)
            GLES20.glLinkProgram(linked)
            val status = IntArray(1); GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            requireNative(status[0] == GLES20.GL_TRUE)
            return linked
        } catch (error: RuntimeException) {
            if (linked != 0) GLES20.glDeleteProgram(linked)
            throw error
        } finally { GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment) }
    }

    private fun compile(type: Int, text: String): Int {
        val shader = GLES20.glCreateShader(type); requireNative(shader != 0)
        try {
            GLES20.glShaderSource(shader, text); GLES20.glCompileShader(shader)
            val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            requireNative(status[0] == GLES20.GL_TRUE)
            return shader
        } catch (error: RuntimeException) { GLES20.glDeleteShader(shader); throw error }
    }
    private fun current(surface: EGLSurface) { requireNative(EGL14.eglMakeCurrent(display, surface, surface, context)) }
    private fun checkGl() { requireNative(GLES20.glGetError() == GLES20.GL_NO_ERROR) }
    private fun assertOwner() { check(Thread.currentThread() === owner) }
    private fun requireNative(value: Boolean) { if (!value) fail() }
    private fun fail(): Nothing = throw RecordingFailure(RecordingError.CAPTURE_UNAVAILABLE)
    private companion object { const val EGL_RECORDABLE_ANDROID = 0x3142 }
}
