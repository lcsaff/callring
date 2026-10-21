package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*

class LeavesViewModel(private val api: CallringUiApi) : SnapshotViewModel<List<LeaveEntry>>(
    api.changes, setOf(DataDomain.WORK_CALENDAR), api.settings::getLeaves, entities = mapOf(DataDomain.WORK_CALENDAR to setOf("leaves"))
) {
    fun save(command: SaveLeave) = write({ api.settings.saveLeave(command) }, "请假记录已保存")
    fun saveDraft(command: SaveLeave, base: SaveLeave?) = write({ api.edits.leave(base, command) }, "请假记录已保存")
    fun delete(id: Long) = write({ api.settings.deleteLeave(id) }, "请假记录已删除")
}
