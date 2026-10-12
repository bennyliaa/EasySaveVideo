package com.easysavevideo.app.library.export

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.easysavevideo.library.*
import com.easysavevideo.library.export.*

/** Same storage owner/lock/DB. Never creates library media or updates the default directory. */
internal class AndroidDirectoryBatchJournal(private val database:()->SQLiteDatabase,private val lock:Any,private val closed:()->Boolean):DirectoryBatchJournal {
    override fun admit(plan:DirectoryBatchPlan)=guard { transaction { db ->
        val existing=phase(plan.batchId)
        if(existing!=null) { require(load(plan.batchId).requests==plan.requests && load(plan.batchId).folderGrantId==plan.folderGrantId);return@transaction }
        db.rawQuery("SELECT COUNT(*) FROM library_directory_batch WHERE phase='ADMITTED'",null).use { require(it.moveToFirst() && it.getInt(0)<ExportLimits.MAX_ACTIVE) }
        db.rawQuery("SELECT retired,invalidated FROM ordinary_folder_grant WHERE folder_grant_id=?",arrayOf(plan.folderGrantId)).use {
            require(it.moveToFirst() && it.getInt(0)==0 && it.getInt(1)==0)
        }
        db.insertOrThrow("library_directory_batch",null,ContentValues().apply {
            put("batch_id",plan.batchId);put("folder_grant_id",plan.folderGrantId);put("phase","ADMITTED")
        })
        plan.requests.forEachIndexed { index,request ->
            require(database().rawQuery("SELECT 1 FROM library_export WHERE operation_id=?",arrayOf(request.operationId)).use{!it.moveToFirst()})
            db.insertOrThrow("library_directory_item",null,ContentValues().apply {
                put("operation_id",request.operationId);put("batch_id",plan.batchId);put("ordinal",index)
                put("item_id",request.source.itemId);put("revision",request.source.revision);put("generation",request.source.representationGeneration)
                put("grant_id",request.destination.grantId);put("started",0);putNull("outcome")
            })
        }
    } }
    override fun stopped(batchId:String)=guard { requireNotNull(phase(batchId))!="ADMITTED" }
    override fun stop(batchId:String)=guard { LibraryIds.requireValid(batchId);database().execSQL("UPDATE library_directory_batch SET phase='STOPPED' WHERE batch_id=? AND phase='ADMITTED'",arrayOf(batchId)) }
    override fun outcome(operationId:String):BatchTransferStatus?=guard {
        database().rawQuery("SELECT outcome FROM library_directory_item WHERE operation_id=?",arrayOf(operationId)).use {
            require(it.moveToFirst());if(it.isNull(0))null else BatchTransferStatus.valueOf(it.getString(0))
        }
    }
    override fun started(operationId:String)=guard {
        database().rawQuery("SELECT started FROM library_directory_item WHERE operation_id=?",arrayOf(operationId)).use {require(it.moveToFirst());it.getInt(0)==1}
    }
    override fun start(plan:DirectoryBatchPlan,operationId:String):Boolean=guard { transaction { db ->
        require(load(plan.batchId).requests==plan.requests && load(plan.batchId).folderGrantId==plan.folderGrantId)
        if(stopped(plan.batchId))return@transaction false
        db.update("library_directory_item",ContentValues().apply{put("started",1)},"batch_id=? AND operation_id=? AND started=0 AND outcome IS NULL",arrayOf(plan.batchId,operationId))==1
    } }
    override fun finish(plan:DirectoryBatchPlan,operationId:String,status:BatchTransferStatus)=guard {
        require(plan.requests.any{it.operationId==operationId})
        require(database().update("library_directory_item",ContentValues().apply{put("outcome",status.name)},"batch_id=? AND operation_id=? AND outcome IS NULL",arrayOf(plan.batchId,operationId))==1)
        Unit
    }
    override fun complete(batchId:String)=guard { transaction { db ->
        db.rawQuery("SELECT COUNT(*) FROM library_directory_item WHERE batch_id=? AND outcome IS NULL",arrayOf(batchId)).use {require(it.moveToFirst() && it.getInt(0)==0)}
        db.execSQL("UPDATE library_directory_batch SET phase='FINISHED' WHERE batch_id=? AND phase='ADMITTED'",arrayOf(batchId))
    } }
    fun folderForOperation(operationId:String):String?=guard {
        database().rawQuery("SELECT b.folder_grant_id FROM library_directory_batch b JOIN library_directory_item i ON i.batch_id=b.batch_id WHERE i.operation_id=?",arrayOf(operationId)).use {
            if(!it.moveToFirst())null else it.getString(0).also(LibraryIds::requireValid)
        }
    }
    /** Every interrupted batch stops before ordinary export recovery. Never restart waiting items. */
    fun stopInterrupted():List<String> = guard { transaction { db ->
        val active=db.rawQuery("SELECT batch_id FROM library_directory_batch WHERE phase='ADMITTED' LIMIT 33",null).use { c ->buildList {while(c.moveToNext())add(c.getString(0))} }
        require(active.size<=ExportLimits.MAX_ACTIVE)
        active.forEach(::stop)
        // An attempt can finish with an uncertain/PREPARED export row. Neither startup nor
        // explicit recovery may turn that historical directory batch into a new create.
        db.execSQL("UPDATE library_directory_batch SET phase='STOPPED' WHERE phase='FINISHED' AND EXISTS(SELECT 1 FROM library_directory_item i JOIN library_export e ON e.operation_id=i.operation_id WHERE i.batch_id=library_directory_batch.batch_id AND e.phase IN ('PREPARED','CREATE_INTENT','COPYING','VERIFYING','PUBLISH_INTENT','CLEANUP_PENDING','RECONCILIATION_REQUIRED'))")
        val ids=db.rawQuery("SELECT i.operation_id FROM library_directory_item i JOIN library_export e ON e.operation_id=i.operation_id WHERE e.phase IN ('PREPARED','CREATE_INTENT','COPYING','VERIFYING','PUBLISH_INTENT','CLEANUP_PENDING','RECONCILIATION_REQUIRED') LIMIT 33",null).use { c ->buildList {while(c.moveToNext())add(c.getString(0))} }
        require(ids.size<=ExportLimits.MAX_ACTIVE)
        ids
    } }
    fun interruptedCount():Int=guard {
        database().rawQuery("SELECT COUNT(*) FROM library_directory_batch WHERE phase='STOPPED'",null).use{require(it.moveToFirst());it.getInt(0)}
    }
    private fun phase(id:String):String? {LibraryIds.requireValid(id);return database().rawQuery("SELECT phase FROM library_directory_batch WHERE batch_id=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0) else null}}
    private fun load(id:String):DirectoryBatchPlan {
        val folder=database().rawQuery("SELECT folder_grant_id FROM library_directory_batch WHERE batch_id=?",arrayOf(id)).use{require(it.moveToFirst());it.getString(0)}
        val requests=database().rawQuery("SELECT operation_id,item_id,revision,generation,grant_id,ordinal FROM library_directory_item WHERE batch_id=? ORDER BY ordinal LIMIT 101",arrayOf(id)).use { c ->
            buildList {while(c.moveToNext()){require(c.getInt(5)==size);add(ExportRequest(c.getString(0),MediaReadRef(c.getString(1),c.getLong(2),c.getLong(3)),ExportDestination(ExportKind.SAF,c.getString(4))))}}
        }
        return DirectoryBatchPlan(id,folder,requests)
    }
    private inline fun <T> guard(block:()->T):T=synchronized(lock) {
        if(closed())throw ExportFailure(ExportError.IO)
        try{block()}catch(f:ExportFailure){throw f}catch(_:Exception){throw ExportFailure(ExportError.RECOVERY_REQUIRED)}
    }
    private inline fun <T> transaction(block:(SQLiteDatabase)->T):T {
        val db=database();db.beginTransaction()
        try{val result=block(db);db.setTransactionSuccessful();return result}finally{db.endTransaction()}
    }
}
