package com.easysavevideo.app.discovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.easysavevideo.discovery.*
import com.easysavevideo.verification.local.LocalDynamicRenderer
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener

/** Fresh inline-only view. Synthetic origin avoids reading the browsing profile's origin cookies. */
class AndroidLocalVerificationRenderer(context:Context):LocalDynamicRenderer {
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private val busy=AtomicBoolean(false)
    override fun isPhysicallyIdle()=!busy.get()
    override fun render(html:ByteArray,request:EngineRequest,operation:DiscoveryOperation):ByteArray? {
        if (Looper.myLooper()==Looper.getMainLooper() || html.size !in 1..65536 || !request.dynamicAllowed ||
            !busy.compareAndSet(false,true)) return null
        val done=CountDownLatch(1);val cleaned=CountDownLatch(1)
        val view=AtomicReference<WebView?>();val value=AtomicReference<ByteArray?>()
        val completedScript=AtomicBoolean(false);val stopped=AtomicBoolean(false)
        val observationStarted=AtomicBoolean(false)
        fun current()=!stopped.get() && !operation.cancellation.isCancelled &&
            System.nanoTime()/1_000_000<request.deadlineMonotonicMillis
        main.post {
            if(!current()) {busy.set(false);done.countDown();cleaned.countDown();return@post}
            try {
                val web=WebView(context);view.set(web)
                web.settings.apply {
                    javaScriptEnabled=true;domStorageEnabled=false;databaseEnabled=false
                    allowFileAccess=false;allowContentAccess=false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs=false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs=false
                    blockNetworkLoads=true;blockNetworkImage=true
                    mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture=true
                    setSupportMultipleWindows(false);javaScriptCanOpenWindowsAutomatically=false
                    cacheMode=WebSettings.LOAD_NO_CACHE
                    safeBrowsingEnabled=true
                }
                web.setDownloadListener { _,_,_,_,_ -> stopped.set(true);done.countDown() }
                web.webChromeClient=object:WebChromeClient() {
                    override fun onPermissionRequest(p:PermissionRequest) {p.deny()}
                    override fun onGeolocationPermissionsShowPrompt(origin:String,callback:GeolocationPermissions.Callback) {callback.invoke(origin,false,false)}
                    override fun onShowFileChooser(w:WebView,callback:android.webkit.ValueCallback<Array<android.net.Uri>>,params:FileChooserParams):Boolean {callback.onReceiveValue(null);return true}
                    override fun onConsoleMessage(message:ConsoleMessage)=true
                    override fun onJsAlert(w:WebView,url:String,message:String,result:JsResult):Boolean {result.cancel();return true}
                    override fun onJsConfirm(w:WebView,url:String,message:String,result:JsResult):Boolean {result.cancel();return true}
                    override fun onJsPrompt(w:WebView,url:String,message:String,defaultValue:String,result:JsPromptResult):Boolean {result.cancel();return true}
                }
                web.webViewClient=object:WebViewClient() {
                    override fun shouldOverrideUrlLoading(w:WebView,r:WebResourceRequest)=true
                    override fun shouldInterceptRequest(w:WebView,r:WebResourceRequest)=
                        WebResourceResponse("text/plain","utf-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
                    override fun onReceivedSslError(w:WebView,handler:SslErrorHandler,error:android.net.http.SslError) {handler.cancel();done.countDown()}
                    override fun onRenderProcessGone(w:WebView,detail:RenderProcessGoneDetail):Boolean {
                        stopped.set(true);completedScript.set(true);done.countDown();return true
                    }
                    override fun onPageFinished(w:WebView,url:String) {
                        if(!current()) {done.countDown();return}
                        // One bounded observation series; timers/selectors are compiled, not supplied by pages.
                        if(observationStarted.getAndSet(true)) return
                        val observationEnd=minOf(request.deadlineMonotonicMillis-100,System.nanoTime()/1_000_000+800)
                        var attempts=0
                        fun observe() {
                            if(!current()) {done.countDown();return}
                            attempts++
                            completedScript.set(false)
                            w.evaluateJavascript("(function(){var s=document.documentElement.outerHTML;return s.length<=65536?s:null})()") { encoded ->
                                completedScript.set(true)
                                if(!current() || encoded.length>400000) {done.countDown();return@evaluateJavascript}
                                val text=try {JSONTokener(encoded).nextValue() as? String} catch(_:Exception){null}
                                val bytes=text?.toByteArray(Charsets.UTF_8)?.takeIf {it.size<=65536}
                                if(bytes==null) {done.countDown();return@evaluateJavascript}
                                if(text?.contains("<video",ignoreCase=true)==true || attempts>=16 || System.nanoTime()/1_000_000>=observationEnd) {
                                    value.set(bytes);done.countDown()
                                } else main.postDelayed({observe()},50)
                            }
                        }
                        observe()
                    }
                }
                web.loadDataWithBaseURL("https://esv-verification.invalid/",html.toString(Charsets.UTF_8),"text/html","utf-8",null)
            } catch(_:Exception) {completedScript.set(true);done.countDown()}
        }
        try {
            while(current() && !done.await(10,TimeUnit.MILLISECONDS)) { }
        } catch(_:InterruptedException) {Thread.currentThread().interrupt()}
        finally {
            stopped.set(true)
            main.post {
                var destructionReturned=false
                try {
                    view.getAndSet(null)?.let {it.stopLoading();it.removeAllViews();it.destroy()}
                    destructionReturned=true
                } catch(_:Exception) { /* Unproven cleanup keeps the physical slot quarantined. */ }
                finally {
                    // A hung/unfinished renderer cannot be claimed physically idle merely because destroy was called.
                    if(destructionReturned && (completedScript.get() || cleaned.count==0L)) busy.set(false)
                    cleaned.countDown()
                }
            }
            try {cleaned.await(500,TimeUnit.MILLISECONDS)} catch(_:InterruptedException){Thread.currentThread().interrupt()}
        }
        return if(!operation.cancellation.isCancelled && System.nanoTime()/1_000_000<request.deadlineMonotonicMillis &&
            completedScript.get() && !busy.get()) value.get() else null
    }
}
