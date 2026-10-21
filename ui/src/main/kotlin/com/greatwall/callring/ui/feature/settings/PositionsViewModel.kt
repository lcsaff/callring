package com.greatwall.callring.ui.feature.settings

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*

class PositionsViewModel(private val api: CallringUiApi) : SnapshotViewModel<PositionSettings>(
    api.changes, setOf(DataDomain.POSITIONS), api.settings::getPositions
) {
    fun save(command: SavePosition) = write({ api.settings.savePosition(command) }, "职位已保存")
    fun saveDraft(command: SavePosition, base: SavePosition?) = write({ api.edits.position(base, command) }, "职位已保存")
    fun delete(id: Long) = write({ api.settings.deletePosition(id) }, "职位已删除")
    fun mine(id: Long?) = write({ api.settings.setMyPosition(id) }, "我的职位已更新")
}
