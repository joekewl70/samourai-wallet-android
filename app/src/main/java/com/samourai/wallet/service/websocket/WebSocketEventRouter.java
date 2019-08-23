package com.samourai.wallet.service.websocket;

import android.util.Log;

import com.samourai.wallet.service.websocket.events.SocketClosedEvent;
import com.samourai.wallet.service.websocket.events.SocketClosingEvent;
import com.samourai.wallet.service.websocket.events.SocketEvent;
import com.samourai.wallet.service.websocket.events.SocketFailureEvent;
import com.samourai.wallet.service.websocket.events.SocketMessageEvent;
import com.samourai.wallet.service.websocket.events.SocketOpenEvent;
import com.samourai.wallet.util.LogUtil;

import io.reactivex.FlowableEmitter;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;


public class WebSocketEventRouter extends WebSocketListener {
    private static final String TAG = "WebSocketEventRouter";
    private final FlowableEmitter<SocketEvent> emitter;

    public WebSocketEventRouter(FlowableEmitter<SocketEvent> emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        if (!emitter.isCancelled()) {
            emitter.onNext(new SocketOpenEvent(webSocket, response));
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        if (!emitter.isCancelled()) {
            emitter.onNext(new SocketMessageEvent(text));
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        if (!emitter.isCancelled()) {
            emitter.onNext(new SocketMessageEvent(bytes));
        }
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        if (!emitter.isCancelled()) {
            emitter.onNext(new SocketClosingEvent(code, reason));
        }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        if (!emitter.isCancelled()) {
            emitter.onNext(new SocketClosedEvent(code, reason));
        }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        LogUtil.info(TAG, "onFailure: ".concat(t.getMessage()));
//        Log.i(TAG, "onFailure: ".concat(response.toString()));
        if (!emitter.isCancelled()) {
            emitter.onNext(new SocketFailureEvent(t, response));
        }
    }
}
