package com.greatwall.callring.data

/** SQLite NOCASE is ASCII-only. GLOB classes preserve Kotlin's Unicode ignoreCase semantics. */
internal object UnicodeSearchPattern {
    private val equivalents by lazy {
        val groups=mutableMapOf<Int,MutableSet<Int>>()
        for(point in 0..Character.MAX_CODE_POINT){
            val upper=Character.toUpperCase(point);val lower=Character.toLowerCase(point)
            if(upper!=point||lower!=point)groups.getOrPut(Character.toLowerCase(upper)){linkedSetOf()}.addAll(listOf(point,upper,lower))
        }
        groups
    }
    fun contains(text:String)=buildString {
        append('*')
        text.codePoints().forEach{point->
            val group=equivalents[Character.toLowerCase(Character.toUpperCase(point))]
            if(group!=null&&group.size>1){append('[');group.forEach{appendCodePoint(it)};append(']')}
            else when(point){91->append("[[]");42->append("[*]");63->append("[?]");else->appendCodePoint(point)}
        }
        append('*')
    }
}
