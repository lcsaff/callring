package com.greatwall.callring.application

import com.greatwall.callring.application.api.DocumentRef
import com.greatwall.callring.application.ports.DocumentStore
import java.io.OutputStream
import java.security.MessageDigest
import java.util.IdentityHashMap

/** Bounded, one-use byte proof for the exact document grant used by an export. */
class ReadbackDocumentStore(private val delegate:DocumentStore):DocumentStore by delegate {
    private data class Proof(val bytes:Long,val digest:ByteArray)
    private val proofs=IdentityHashMap<DocumentRef,Proof>()
    private val order=ArrayDeque<DocumentRef>()
    override fun <T> write(destination:DocumentRef,block:(OutputStream)->T):T {
        synchronized(proofs){proofs.remove(destination);order.removeAll{it===destination}}
        val hash=MessageDigest.getInstance("SHA-256");var bytes=0L
        val result=delegate.write(destination){out->block(object:OutputStream(){
            override fun write(value:Int){out.write(value);hash.update(value.toByte());bytes++}
            override fun write(value:ByteArray,offset:Int,length:Int){out.write(value,offset,length);hash.update(value,offset,length);bytes+=length}
            override fun flush()=out.flush()
        })}
        synchronized(proofs){while(order.size>=16)proofs.remove(order.removeFirst());proofs[destination]=Proof(bytes,hash.digest());order.addLast(destination)}
        return result
    }
    fun verifyWrite(destination:DocumentRef):Boolean {
        val expected=synchronized(proofs){order.removeAll{it===destination};proofs.remove(destination)}?:return false
        return try{delegate.read(destination){input->
            val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);var count=0L
            while(true){val n=input.read(buffer);if(n<0)break;count+=n;if(count>expected.bytes)return@read false;hash.update(buffer,0,n)}
            count==expected.bytes&&MessageDigest.isEqual(hash.digest(),expected.digest)
        }}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){false}
    }
}
