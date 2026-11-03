package com.azayn.voiptest;

import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccountHandle;

/** Self-managed calls, so Telecom (not this app) owns the audio mode, like WhatsApp on Samsung. */
public class CallService extends ConnectionService {
    static Connection current;

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        Connection c = new Connection() {
            @Override
            public void onDisconnect() {
                setDisconnected(new android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL));
                destroy();
            }
        };
        c.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        c.setAudioModeIsVoip(true);
        c.setAddress(request.getAddress(), android.telecom.TelecomManager.PRESENTATION_ALLOWED);
        c.setActive();
        current = c;
        return c;
    }
}
