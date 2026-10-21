package com.greatwall.callring

import com.greatwall.callring.application.runtime.ReadbackDocumentStore
import com.greatwall.callring.contracts.common.DocumentRef
import com.greatwall.callring.application.ports.DocumentStore
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class ReadbackDocumentStoreTest {
    private class Memory:DocumentStore {
        var bytes=byteArrayOf();var fail=false
        override fun <T> read(source:DocumentRef,block:(InputStream)->T):T {if(fail)throw IOException();return block(bytes.inputStream())}
        override fun <T> write(destination:DocumentRef,block:(OutputStream)->T):T {val out=ByteArrayOutputStream();return block(out).also{bytes=out.toByteArray()}}
        override fun readCalendar(source:DocumentRef)=error("unused")
    }
    @Test fun readbackIsOneUseAndRejectsSameLengthCorruptionAndTrailingBytes() {
        val memory=Memory();val store=ReadbackDocumentStore(memory);val ref=DocumentRef("grant")
        store.write(ref){it.write("hello".toByteArray())};assertTrue(store.verifyWrite(ref));assertFalse(store.verifyWrite(ref))
        store.write(ref){it.write("hello".toByteArray())};memory.bytes="world".toByteArray();assertFalse(store.verifyWrite(ref))
        store.write(ref){it.write("hello".toByteArray())};memory.bytes+="!".toByteArray();assertFalse(store.verifyWrite(ref))
        store.write(ref){it.write("hello".toByteArray())};memory.fail=true;assertFalse(store.verifyWrite(ref))
    }
    @Test fun equalUrisDoNotSubstituteAnotherGrantOrWriteAndCapacityIsBounded() {
        val memory=Memory();val store=ReadbackDocumentStore(memory);val first=DocumentRef("grant");val second=DocumentRef("grant")
        store.write(first){it.write(1)};store.write(second){it.write(2)}
        assertFalse(store.verifyWrite(first));assertTrue(store.verifyWrite(second))
        val refs=(0..16).map{DocumentRef("$it")};refs.forEach{r->store.write(r){it.write(1)}}
        assertFalse(store.verifyWrite(refs.first()));assertTrue(store.verifyWrite(refs.last()))
    }
}
