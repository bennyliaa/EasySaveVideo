package com.easysavevideo.verification

/** Bounded protocol facade. Transport URL policy and exact v1 validation remain unchanged. */
object VerificationWireCodec {
    fun request(id:String,generation:String,page:String,budget:Long,dynamic:Boolean):ByteArray =
        VerificationProtocol.request(id,generation,page,budget,dynamic)
    fun response(bytes:ByteArray,id:String,generation:String,page:String,dynamic:Boolean,
        check:()->Unit):VerificationResult = VerificationProtocol.response(bytes,id,generation,page,dynamic,check)
}
