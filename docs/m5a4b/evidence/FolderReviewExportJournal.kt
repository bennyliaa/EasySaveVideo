package com.easysavevideo.app.library.export
import com.easysavevideo.library.export.*
/** A durable-ack-loss programmable double, never substituted for Android SQLite acceptance. */
class AndroidExportJournal : ExportJournal {
    data class Target(val operationId:String,val kind:ExportKind,val grantId:String,val nonce:String,val uri:String?,val flags:Int)
    val records=linkedMapOf<String,ExportRecord>(); val targets=linkedMapOf<String,Target>(); val events=mutableListOf<String>()
    var failBindBefore=false; var failBindAfter=false; var fatalAfterCreateIntent=false
    override fun create(request:ExportRequest):ExportRecord = records[request.operationId]?.also { require(it.request==request) } ?: ExportRecord(request).also { records[request.operationId]=it }
    override fun get(operationId:String)=records[operationId]
    override fun put(record:ExportRecord) { require(records.getValue(record.operationId).request==record.request); records[record.operationId]=record; events+="phase:${record.phase}"; if(fatalAfterCreateIntent&&record.phase==ExportPhase.CREATE_INTENT) throw AssertionError("SYNTHETIC_CRASH") }
    override fun unfinished(limit:Int)=records.values.filter { !it.phase.terminal }.take(limit)
    fun reserve(request:ExportRequest):Target {
        require(records.getValue(request.operationId).request==request)
        return targets.getOrPut(request.operationId) { Target(request.operationId,request.destination.kind,request.destination.grantId,"00000000-0000-0000-0000-000000000111",null,0) }
    }
    fun target(operationId:String)=targets[operationId]
    fun bind(operationId:String,grantId:String,uri:String,flags:Int):Target {
        events+="bind-attempt"; if(failBindBefore) error("SYNTHETIC")
        val row=targets.getValue(operationId); require(row.grantId==grantId&&(row.uri==null||row.uri==uri))
        val next=row.copy(uri=uri,flags=flags); targets[operationId]=next; events+="bound"
        if(failBindAfter) error("SYNTHETIC")
        return next
    }
    fun bindSafOutput(request:ExportRequest,uri:String,flags:Int):Target { reserve(request); return bind(request.operationId,request.destination.grantId,uri,flags) }
}
fun safeExportLabel(value:CharSequence,limit:Int)=value.toString().take(limit)
