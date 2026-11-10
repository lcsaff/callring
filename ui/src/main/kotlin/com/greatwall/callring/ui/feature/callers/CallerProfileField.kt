package com.greatwall.callring.ui.feature.callers

import com.greatwall.callring.contracts.callers.CallerDraft
import com.greatwall.callring.contracts.callers.CallerFieldMetadata

enum class CallerProfileField(val label: String, val key: String, val hasMetadata: Boolean = false) {
    NAME("姓名", "name", true),
    DEPARTMENT("部门", "department", true),
    SELF_REPORTED_POSITION("自述职位", "selfReportedPosition", true),
    REGION("所属地区", "region", true),
    POSITION("职位级别", "positionId"),
    WHITELIST("白名单", "whitelisted");

    /** Restrict a field editor to its own value and optional dispute flag. */
    internal fun applyEdit(base: CallerDraft, value: CallerDraft): CallerDraft {
        val edited = when (this) {
            NAME -> base.copy(name = value.name)
            DEPARTMENT -> base.copy(department = value.department)
            SELF_REPORTED_POSITION -> base.copy(selfReportedPosition = value.selfReportedPosition)
            REGION -> base.copy(region = value.region)
            POSITION -> base.copy(positionId = value.positionId)
            WHITELIST -> base.copy(whitelisted = value.whitelisted)
        }
        val previous = base.identityMetadata[key] ?: CallerFieldMetadata()
        val disputed = value.identityMetadata[key]?.disputed ?: false
        return if (hasMetadata && disputed != previous.disputed)
            edited.copy(identityMetadata = edited.identityMetadata + (key to previous.copy(disputed = disputed)))
        else edited
    }
}
