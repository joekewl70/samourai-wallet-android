package com.samourai.wallet.service.websocket.events;


import com.samourai.wallet.service.websocket.SocketEventTypeEnum;

public class SocketClosedEvent extends SocketEvent {

    private final int code;
    private final String reason;

    public SocketClosedEvent(int code, String reason) {
        super(SocketEventTypeEnum.CLOSED);
        this.code = code;
        this.reason = reason;
    }

    public int getCode() {
        return code;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "SocketClosedEvent{" +
                "code=" + code +
                ", reason='" + reason + '\'' +
                '}';
    }
}
