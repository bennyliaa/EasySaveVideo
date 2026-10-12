package com.easysavevideo.app.library.export

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class DirectoryBatchResourcesTest {
    private fun read(locale:String):Map<String,String> {
        val root=listOf(File("src/main/res"),File("app/src/main/res")).first{it.isDirectory}
        val f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        val nodes=f.newDocumentBuilder().parse(File(root,"$locale/directory_batch.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate{nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent}
    }
    @Test fun everyFolderNoticeAndActionHasPairedNonemptyCopy() {
        val en=read("values");val zh=read("values-zh-rCN");assertEquals(11,en.size);assertEquals(en.keys,zh.keys)
        en.keys.forEach{assertTrue(en.getValue(it).isNotBlank());assertTrue(zh.getValue(it).isNotBlank())}
    }
    @Test fun partialCompletionAndPermissionRetentionAreNotPromisedAsRollback() {
        assertTrue(read("values").getValue("directory_copying").contains("not an atomic batch"))
        assertTrue(read("values-zh-rCN").getValue("directory_stopped").contains("已完成副本保留"))
        assertTrue(read("values").getValue("directory_confirm_notice").contains("does not change your default"))
    }
    @Test fun stoppedHistoryIsNotAutomaticallyResumedOrMislabelledAsSuccessfulCopy() {
        assertTrue(read("values").getValue("directory_interrupted").contains("will not automatically resume"))
        assertTrue(read("values-zh-rCN").getValue("directory_completed").contains("未验证或不明的副本不算确认完成"))
    }
}
