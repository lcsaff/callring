package com.greatwall.callring.application

import com.greatwall.callring.application.api.*

/** Shared by UI reports and source-level streaming aggregates. Input order is ascending event time. */
class StreamingReportAccumulator(private val settings:ScoreSettings,private val range:TimeRange,private val includeSaturatedMax:Boolean=false) {
    private var previous:Long?=null
    private var count=0L;private var off=0L;private var repeats=0L
    private val totals=ReportTotalsAccumulator()
    fun accept(at:Long,counted:Boolean,working:Boolean,duration:Long?,result:CallResult){
        if(at<range.startInclusiveEpochMillis||!(at<range.endExclusiveEpochMillis||(includeSaturatedMax&&at==Long.MAX_VALUE&&range.endExclusiveEpochMillis==Long.MAX_VALUE)))return
        totals.accept(counted,working,duration,result)
        if(!counted)return
        previous?.let { require(at>=it);val gap=at-it;if(gap>=0&&gap<=settings.repeatMinutes*60000L)repeats++ }
        previous=at;count++;if(!working)off++
    }
    fun score()=ScoreSummary(if(count==0L)null else (100L-off*settings.offHoursWeight-repeats*settings.repeatWeight).coerceIn(0,100).toInt(),count,off,repeats,settings.offHoursWeight,settings.repeatWeight,settings.repeatMinutes,range)
    fun totals()=totals.value()
}
class ReportTotalsAccumulator {
    private var count=0L;private var work=0L;private var duration=0L;private var missing=0L;private var missed=0L;private var rejected=0L;private var pending=0L
    fun accept(counted:Boolean,working:Boolean,seconds:Long?,result:CallResult){
        if(result==CallResult.PENDING||result==CallResult.UNRESOLVED)pending++
        if(!counted)return
        count++;if(working)work++
        if(seconds==null)missing++ else duration=try{Math.addExact(duration,seconds)}catch(_:ArithmeticException){Long.MAX_VALUE}
        if(result==CallResult.MISSED)missed++
        if(result==CallResult.REJECTED||result==CallResult.BLOCKED)rejected++
    }
    fun value(offOverride:Long=count-work)=ReportTotals(count,work,offOverride,duration,missing,missed,rejected,pending)
}
