package com.samourai.wallet.service.websocket;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.gson.Gson;
import com.samourai.wallet.service.websocket.events.SocketClosedEvent;
import com.samourai.wallet.service.websocket.events.SocketClosingEvent;
import com.samourai.wallet.service.websocket.events.SocketEvent;
import com.samourai.wallet.service.websocket.events.SocketFailureEvent;
import com.samourai.wallet.service.websocket.events.SocketMessageEvent;
import com.samourai.wallet.service.websocket.events.SocketOpenEvent;


import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.processors.PublishProcessor;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okio.ByteString;

public class RxWebSocket {

    private static final String TAG = "WebSocket";

    private final WebSocketOnSubscribe webSocketOnSubscribe;
    private PublishProcessor<SocketEvent> socketEventProcessor = PublishProcessor.create();
    private CompositeDisposable disposables = new CompositeDisposable();
    private CompositeDisposable connectionDisposables = null;
    private WebSocket webSocket = null;
    private boolean isOpen = false;

    public RxWebSocket(@NonNull String connectionUrl) {
        Log.i(TAG, "WebSocket URL : ".concat(connectionUrl));
        this.webSocketOnSubscribe = new WebSocketOnSubscribe(connectionUrl);
    }

    public RxWebSocket(@NonNull OkHttpClient client, @NonNull String connectionUrl) {
        Log.i(TAG, "WebSocket URL: ".concat(connectionUrl));
        this.webSocketOnSubscribe = new WebSocketOnSubscribe(client, connectionUrl);
    }

    private Flowable<SocketEvent> getEventSource() {
        return socketEventProcessor.onErrorResumeNext(throwable -> {
            Log.e(TAG, throwable.getMessage());
            throwable.printStackTrace();
            socketEventProcessor = PublishProcessor.create();
            return socketEventProcessor;
        });
    }

    public Flowable<SocketOpenEvent> onOpen() {
        isOpen = true;
        return getEventSource()
                .ofType(SocketOpenEvent.class);
    }

    public Flowable<SocketClosedEvent> onClosed() {
        isOpen = false;
        return getEventSource()
                .ofType(SocketClosedEvent.class);
    }

    public Flowable<SocketClosingEvent> onClosing() {
        return getEventSource()
                .ofType(SocketClosingEvent.class);
    }

    public Flowable<SocketFailureEvent> onFailure() {
        isOpen = false;
        return getEventSource()
                .ofType(SocketFailureEvent.class);
    }

    public Flowable<SocketMessageEvent> onTextMessage() {
        return getEventSource()
                .ofType(SocketMessageEvent.class)
                .filter(SocketMessageEvent::isText);
    }

    public Flowable<SocketMessageEvent> onBinaryMessage() {
        return getEventSource()
                .ofType(SocketMessageEvent.class)
                .filter(event -> !event.isText());
    }

    public synchronized void connect() {
        connectionDisposables = new CompositeDisposable();
        Disposable webSocketInstanceDisposable = getEventSource()
                .ofType(SocketOpenEvent.class)
                .firstElement()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .subscribe(
                        socketOpenEvent -> webSocket = socketOpenEvent.getWebSocket(),
                        throwable -> {
                            Log.e(TAG, throwable.getMessage());
                            throwable.printStackTrace();
                        });

        Disposable connectionDisposable = Flowable.create(webSocketOnSubscribe, BackpressureStrategy.BUFFER)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.computation())
                .subscribe(
                        event -> socketEventProcessor.onNext(event),
                        throwable -> {
                            Log.e(TAG, throwable.getMessage());
                            throwable.printStackTrace();
                        });
        connectionDisposables.add(webSocketInstanceDisposable);
        connectionDisposables.add(connectionDisposable);
        disposables.add(connectionDisposable);
    }

    public synchronized Single<Boolean> sendMessage(@NonNull Gson gson, @Nullable Object payload) {
        return Single.fromCallable(() -> {
            if (webSocket != null) {
                String jsonBody = new Gson().toJson(payload);
                return webSocket.send(jsonBody);
            } else {
                throw new Exception("WebSocket not connected!");
            }
        });
    }

    public synchronized Single<Boolean> sendMessage(@Nullable String content) {
        return Single.fromCallable(() -> {
            if (webSocket != null) {
                return webSocket.send(content);
            } else {
                throw new Exception("WebSocket not connected!");
            }
        });
    }

    public synchronized Single<Boolean> sendMessage(@NonNull ByteString bytes) {
        return Single.fromCallable(() -> {
            if (webSocket != null) {
                return webSocket.send(bytes);
            } else {
                throw new Exception("WebSocket not connected!");
            }
        });
    }

    public synchronized Single<Boolean> close() {
        return Single.fromCallable(() -> {
            if (webSocket != null) {
                disposables.add(getEventSource()
                        .ofType(SocketClosedEvent.class)
                        .subscribe(event -> {
                            connectionDisposables.clear();
                            disposables.clear();
                        }, Throwable::printStackTrace));
                return webSocket.close(1000, "Bye");
            } else {
                throw new Exception("WebSocket not connected!");
            }
        }).doOnSuccess(success -> webSocket = null);
    }

    public synchronized Single<Boolean> close(int code, @Nullable String reason) {
        return Single.fromCallable(() -> {
            if (webSocket != null) {
                disposables.add(getEventSource()
                        .ofType(SocketClosedEvent.class)
                        .subscribe(event -> {
                            connectionDisposables.clear();
                            disposables.clear();
                        }, Throwable::printStackTrace));
                return webSocket.close(code, reason);
            } else {
                throw new RuntimeException("WebSocket not connected!");
            }
        }).doOnSuccess(success -> webSocket = null);
    }

    public boolean isOpen() {
        return isOpen;
    }
}
