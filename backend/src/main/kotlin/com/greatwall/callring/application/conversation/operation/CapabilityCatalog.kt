package com.greatwall.callring.application.conversation.operation

import com.greatwall.callring.application.conversation.execution.json
import com.greatwall.callring.application.conversation.execution.s
import com.greatwall.callring.application.conversation.query.json

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.ai.*

data class DiscoveryPage(val tools:List<OperationDefinition>,val nextCursor:String?,val complete:Boolean,val catalogVersion:String="2") {
    fun json()=aiObject("catalogVersion" to aiString(catalogVersion),"tools" to AiJson.Arr(tools.map{aiObject("id" to aiString(it.operationCode),"name" to aiString(it.name),"description" to aiString(it.description),"write" to aiBool(it.write),"schema" to it.schema)}),"nextCursor" to (nextCursor?.let(::aiString)?:AiJson.Null),"complete" to aiBool(complete))
}
class CapabilityCatalog(private val registry:ToolRegistry) {
    fun discover(category:String?=null,exactNames:List<String> = emptyList(),query:String?=null,cursor:String?=null,limit:Int=8):DiscoveryPage {
        require(limit in 1..12)
        val terms=query.orEmpty().trim().lowercase().split(Regex("\\s+")).filter{it.isNotEmpty()}
        val names=exactNames.toSet()
        val rows=registry.definitions.filter{d->
            (category.isNullOrBlank()||category=="all"||d.name.substringBefore('_').equals(category,true)||d.description.contains(category)) &&
            (names.isEmpty()||d.name in names) && (terms.isEmpty()||terms.any{d.name.lowercase().contains(it)||d.description.lowercase().contains(it)})
        }
        val offset=cursor?.removePrefix("catalog2:")?.toIntOrNull()?:if(cursor==null)0 else error("ai.cursor_stale")
        require(offset in 0..rows.size)
        val page=rows.drop(offset).take(limit);val end=offset+page.size
        return DiscoveryPage(page,if(end<rows.size) "catalog2:$end" else null,end==rows.size)
    }
}
