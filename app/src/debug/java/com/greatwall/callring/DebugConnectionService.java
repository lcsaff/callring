package com.greatwall.callring;

import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

public class DebugConnectionService extends ConnectionService {
    static Connection current;
    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle manager, ConnectionRequest request) {
        Connection connection = new Connection() {
            @Override public void onAnswer() { setActive(); EventLog.add(DebugConnectionService.this, "模拟来电已接听"); }
            @Override public void onReject() { end(); }
            @Override public void onDisconnect() { end(); }
            private void end() {
                setDisconnected(new DisconnectCause(DisconnectCause.LOCAL)); destroy();
                current = null; EventLog.add(DebugConnectionService.this, "模拟来电已结束");
            }
        };
        connection.setAddress(request.getAddress(), TelecomManager.PRESENTATION_ALLOWED);
        connection.setCallerDisplayName("规则铃声测试", TelecomManager.PRESENTATION_ALLOWED);
        connection.setAudioModeIsVoip(true);
        connection.setRinging(); current = connection;
        EventLog.add(this, "已建立模拟来电");
        return connection;
    }
}
