package com.greatwall.callring;

public final class PositionRelationPolicy {
    public static String compare(Integer callerLevel, Integer myLevel) {
        if (callerLevel == null || myLevel == null || callerLevel <= 0 || myLevel <= 0) return "—";
        if (callerLevel < myLevel) return "↑";
        if (callerLevel > myLevel) return "↓";
        return "＝";
    }
}
