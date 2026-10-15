package com.greatwall.callring;

final class RulePolicy {
    static boolean matches(String number) { return number != null && number.matches("3[0-9]{4}"); }
    static boolean shouldReplace(String number, boolean incoming, boolean enabled,
            boolean phonePermission, boolean audible, boolean ongoingCall) {
        return incoming && enabled && phonePermission && audible && !ongoingCall && matches(number);
    }
}
