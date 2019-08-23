package com.samourai.wallet.service;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.samourai.wallet.BuildConfig;
import com.samourai.wallet.MainActivity2;
import com.samourai.wallet.R;
import com.samourai.wallet.SamouraiWallet;
import com.samourai.wallet.api.APIFactory;
import com.samourai.wallet.bip47.BIP47Meta;
import com.samourai.wallet.bip47.BIP47Util;
import com.samourai.wallet.bip47.rpc.PaymentCode;
import com.samourai.wallet.hd.HD_WalletFactory;
import com.samourai.wallet.segwit.BIP49Util;
import com.samourai.wallet.segwit.BIP84Util;
import com.samourai.wallet.service.websocket.RxWebSocket;
import com.samourai.wallet.tor.TorManager;
import com.samourai.wallet.util.AddressFactory;
import com.samourai.wallet.util.LogUtil;
import com.samourai.wallet.util.MonetaryUtil;
import com.samourai.wallet.util.NotificationsFactory;

import org.bitcoinj.crypto.MnemonicException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class WebSocketHandler {

    private Context context;
    private static final String TAG = "WebSocketHandler";
    private String[] addrs = null;
    private List<String> addrSubs = new ArrayList<>();
    private RxWebSocket rxWebSocket;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private static List<String> seenHashes = new ArrayList<>();
    private String WEB_SOCKET_END_POINT = SamouraiWallet.getInstance().isTestNet() ? "wss://api.samourai.io/test/v2/inv" : "wss://api.samourai.io/v2/inv";


    WebSocketHandler(Context mContext) {
        this.context = mContext;

//        LogUtil.info(TAG, "onStartJob: ");
//        Disposable aliveDisposable = Completable.fromCallable(() -> {
//            APIFactory.getInstance(context).stayingAlive();
//            return true;
//        }).subscribeOn(Schedulers.io())
//                .subscribeOn(AndroidSchedulers.mainThread())
//                .subscribe();


        //Since WebSocket over tor seems flaky
        //The service will listen to Tor status, if tor is enabled the service will be terminated
        Disposable TorDisposable = TorManager.getInstance(context).torStatus
                .subscribeOn(Schedulers.io())
                .subscribeOn(AndroidSchedulers.mainThread())
                .subscribe(connection_states -> {
                    if (connection_states == TorManager.CONNECTION_STATES.CONNECTING || connection_states == TorManager.CONNECTION_STATES.CONNECTED) {
                        if (rxWebSocket != null && rxWebSocket.isOpen()) {
                            this.closeWebSocket();
                            WebSocketJobService.cancelJobs(context);
                        }
                    }
                });


        try {
            if (HD_WalletFactory.getInstance(context).get() == null) {
                return;
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } catch (MnemonicException.MnemonicLengthException mle) {
            mle.printStackTrace();
        }

        //
        // prune BIP47 lookbehind
        //
        BIP47Meta.getInstance().pruneIncoming();
        addrSubs = new ArrayList<>();
        addrSubs.add(AddressFactory.getInstance(context).account2xpub().get(0));
        addrSubs.add(BIP49Util.getInstance(context).getWallet().getAccount(0).xpubstr());
        addrSubs.add(BIP84Util.getInstance(context).getWallet().getAccount(0).xpubstr());
        addrSubs.addAll(Arrays.asList(BIP47Meta.getInstance().getIncomingLookAhead(context)));
        addrs = addrSubs.toArray(new String[addrSubs.size()]);


        rxWebSocket = new RxWebSocket(WEB_SOCKET_END_POINT);


        Disposable onOpenDisposable = rxWebSocket.onOpen()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(socketOpenEvent -> subscribe(), Throwable::printStackTrace);

        Disposable onCloseDisposable = rxWebSocket.onClosed()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(socketClosedEvent -> {
                    LogUtil.info(TAG, "Closed");
                }, Throwable::printStackTrace);

        Disposable onClosingDisposable = rxWebSocket.onClosing()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(socketClosingEvent -> {
                    LogUtil.info(TAG, "Closing");
                }, Throwable::printStackTrace);

        Disposable onTextMessageDisposable = rxWebSocket.onTextMessage()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(socketMessageEvent -> {

                    LogUtil.info(TAG, "Message: ".concat(socketMessageEvent.getText()));
                    parseMessagePayload(socketMessageEvent.getText());
                    rxWebSocket.connect();
                }, Throwable::printStackTrace);

        Disposable onFailureDisposable = rxWebSocket.onFailure()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(socketFailureEvent -> {
                    LogUtil.info(TAG, "onFailure: ".concat(socketFailureEvent.getException().getMessage()));
                    rxWebSocket.connect();
                }, Throwable::printStackTrace);


        compositeDisposable.add(onOpenDisposable);
        compositeDisposable.add(onCloseDisposable);
        compositeDisposable.add(onClosingDisposable);
        compositeDisposable.add(onTextMessageDisposable);
        compositeDisposable.add(onFailureDisposable);
//        compositeDisposable.add(aliveDisposable);
        compositeDisposable.add(TorDisposable);

    }

    public void onStart() {
        if (rxWebSocket != null)
            rxWebSocket.connect();

    }


    public void onStop() {
        closeWebSocket();
        compositeDisposable.dispose();
    }


    private void updateBalance(final String rbfHash, final String blkHash) {

        Intent intent = new Intent("com.samourai.wallet.BalanceFragment.REFRESH");
        intent.putExtra("rbf", rbfHash);
        intent.putExtra("notifTx", true);
        intent.putExtra("fetch", true);
        intent.putExtra("hash", blkHash);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

    }

    private void subscribe() {
        LogUtil.info(TAG, "Subscribe");

        send("{\"op\":\"blocks_sub\"}", () -> {

            for (int i = 0; i < addrs.length; i++) {
                if (addrs[i] != null && addrs[i].length() > 0) {
                    send("{\"op\":\"addr_sub\", \"addr\":\"" + addrs[i] + "\"}", () -> {
                    });
                }
            }
        });

    }

    private void send(String message, SendCompleteEvent onSendComplete) {
        Disposable disposable = rxWebSocket.sendMessage(message)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((aBoolean, throwable) -> {
                    onSendComplete.onSendComplete();
                });

        compositeDisposable.add(disposable);
    }

    //callback for SuccessFull WebSocket broadcast
    interface SendCompleteEvent {
        void onSendComplete();
    }

    private void parseMessagePayload(String message) {

        try {
            JSONObject jsonObject = null;
            try {
                jsonObject = new JSONObject(message);
            } catch (JSONException je) {
                jsonObject = null;
            }

            if (jsonObject == null) {
                return;
            }

            String op = (String) jsonObject.get("op");

            if (op.equals("block") && jsonObject.has("x")) {

                JSONObject objX = (JSONObject) jsonObject.get("x");

                String hash = null;

                if (objX.has("hash")) {
                    hash = objX.getString("hash");
                    if (seenHashes.contains(hash)) {
                        return;
                    } else {
                        seenHashes.add(hash);
                    }
                }

                updateBalance(null, hash);

                return;
            }

            if (op.equals("utx") && jsonObject.has("x")) {

                JSONObject objX = (JSONObject) jsonObject.get("x");

                long value = 0L;
                long total_value = 0L;
                long ts = 0L;
                String in_addr = null;
                String out_addr = null;
                String hash = null;


                if (objX.has("time")) {
                    ts = objX.getLong("time");
                }

                if (objX.has("hash")) {
                    hash = objX.getString("hash");
                }

                if (objX.has("inputs")) {
                    JSONArray inputArray = (JSONArray) objX.get("inputs");
                    JSONObject inputObj = null;
                    for (int j = 0; j < inputArray.length(); j++) {
                        inputObj = (JSONObject) inputArray.get(j);

                        if (inputObj.has("prev_out")) {
                            JSONObject prevOutObj = (JSONObject) inputObj.get("prev_out");
                            if (prevOutObj.has("value")) {
                                value = prevOutObj.getLong("value");
                            }
                            if (prevOutObj.has("xpub")) {
                                total_value -= value;
                            } else if (prevOutObj.has("addr")) {
                                if (in_addr == null) {
                                    in_addr = (String) prevOutObj.get("addr");
                                } else {
                                    ;
                                }
                            } else {
                                ;
                            }
                        }
                    }
                }

                if (objX.has("out")) {
                    JSONArray outArray = (JSONArray) objX.get("out");
                    JSONObject outObj = null;
                    for (int j = 0; j < outArray.length(); j++) {
                        outObj = (JSONObject) outArray.get(j);
                        if (outObj.has("value")) {
                            value = outObj.getLong("value");
                        }
                        if ((outObj.has("addr") && BIP47Meta.getInstance().getPCode4Addr(outObj.getString("addr")) != null) ||
                                (outObj.has("pubkey") && BIP47Meta.getInstance().getPCode4Addr(outObj.getString("pubkey")) != null)) {
                            total_value += value;
                            out_addr = outObj.getString("addr");

                            if (BuildConfig.DEBUG)
                                LogUtil.info(TAG, "received from " + out_addr);

                            String pcode = BIP47Meta.getInstance().getPCode4Addr(outObj.getString("addr"));
                            int idx = BIP47Meta.getInstance().getIdx4Addr(outObj.getString("addr"));
                            if (outObj.has("pubkey")) {
                                idx = BIP47Meta.getInstance().getIdx4Addr(outObj.getString("pubkey"));
                                pcode = BIP47Meta.getInstance().getPCode4Addr(outObj.getString("pubkey"));
                            }
                            if (pcode != null && idx > -1) {

                                SimpleDateFormat sd = new SimpleDateFormat("dd MMM");
                                String strTS = sd.format(ts * 1000L);
                                String event = strTS + " " + context.getString(R.string.received) + " " + MonetaryUtil.getInstance().getBTCFormat().format((double) total_value / 1e8) + " BTC";
                                BIP47Meta.getInstance().setLatestEvent(pcode, event);
                                List<String> _addrs = new ArrayList<String>();

                                idx++;
                                for (int i = idx; i < (idx + BIP47Meta.INCOMING_LOOKAHEAD); i++) {
                                    LogUtil.info(TAG, "receive from " + i + ":" + BIP47Util.getInstance(context).getReceivePubKey(new PaymentCode(pcode), i));
                                    BIP47Meta.getInstance().getIdx4AddrLookup().put(BIP47Util.getInstance(context).getReceivePubKey(new PaymentCode(pcode), i), i);
                                    BIP47Meta.getInstance().getPCode4AddrLookup().put(BIP47Util.getInstance(context).getReceivePubKey(new PaymentCode(pcode), i), pcode.toString());

                                    _addrs.add(BIP47Util.getInstance(context).getReceivePubKey(new PaymentCode(pcode), i));
                                }

                                idx--;
                                if (idx >= 2) {
                                    for (int i = idx; i >= (idx - (BIP47Meta.INCOMING_LOOKAHEAD - 1)); i--) {
                                        LogUtil.info(TAG, "receive from " + i + ":" + BIP47Util.getInstance(context).getReceivePubKey(new PaymentCode(pcode), i));
                                        BIP47Meta.getInstance().getIdx4AddrLookup().put(BIP47Util.getInstance(context).getReceivePubKey(new PaymentCode(pcode), i), i);
                                        BIP47Meta.getInstance().getPCode4AddrLookup().put(BIP47Util.getInstance(context).getReceivePubKey(new PaymentCode(pcode), i), pcode.toString());

                                        _addrs.add(BIP47Util.getInstance(context).getReceivePubKey(new PaymentCode(pcode), i));
                                    }
                                }

                                addrs = _addrs.toArray(new String[_addrs.size()]);

                                updateBalance(null, hash);

                            }
                        } else if (outObj.has("addr")) {

                            LogUtil.info(TAG, "addr:" + outObj.getString("addr"));
                            if (outObj.has("xpub") && outObj.getJSONObject("xpub").has("path") && outObj.getJSONObject("xpub").getString("path").startsWith("M/1/")) {
                                return;
                            } else {
                                total_value += value;
                                out_addr = outObj.getString("addr");
                            }
                        } else {
                            ;
                        }
                    }
                }

                String title = context.getString(R.string.app_name);
                if (total_value > 0L) {

                    String finalIn_addr = in_addr;
                    long finalTotal_value = total_value;
                    String finalHash = hash;

                    String finalOut_addr = out_addr;
                    Disposable rbfDisposable = checkForRBF(hash)
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(isRBF -> {

                                String marquee = context.getString(R.string.received_bitcoin) + " " + MonetaryUtil.getInstance().getBTCFormat().format((double) finalTotal_value / 1e8) + " BTC";
                                if (finalIn_addr != null && finalIn_addr.length() > 0) {
                                    marquee += " from " + finalIn_addr;
                                }
                                NotificationsFactory.getInstance(context).setNotification(title, marquee, marquee, R.drawable.ic_samourai_logo_trans2x, MainActivity2.class, 1000);


                                //TODO
                                updateBalance(isRBF ? finalHash : null, null);

                                if (finalOut_addr != null) {
                                    updateReceive(finalOut_addr);
                                }

                            });
                    compositeDisposable.add(rbfDisposable);
                }

            } else {
                ;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    private synchronized Single<Boolean> checkForRBF(String hash) {
        return Single.fromCallable(() -> {
            boolean ret = false;
            try {
                JSONObject obj = APIFactory.getInstance(context).getTxInfo(hash);
                if (obj != null && obj.has("inputs")) {
                    JSONArray inputs = obj.getJSONArray("inputs");
                    for (int i = 0; i < inputs.length(); i++) {
                        JSONObject inputObj = inputs.getJSONObject(i);
                        if (inputObj.has("seq")) {
                            long sequence = inputObj.getLong("seq");
                            if (BigInteger.valueOf(sequence).compareTo(SamouraiWallet.RBF_SEQUENCE_VAL) <= 0) {
                                ret = true;
                                if (BuildConfig.DEBUG)
                                    Log.d(TAG, "return value:" + ret);
                                break;
                            }
                        }
                    }
                }
            } catch (JSONException je) {
                je.printStackTrace();
            }
            return ret;

        });
    }


    private void updateReceive(final String address) {
        new Thread() {
            public void run() {

                Looper.prepare();

                Intent intent = new Intent("com.samourai.wallet.ReceiveFragment.REFRESH");
                intent.putExtra("received_on", address);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);

                Looper.loop();

            }
        }.start();
    }


    private void closeWebSocket() {
        compositeDisposable.add(rxWebSocket.close()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((aBoolean, throwable) -> {
                    //no-op
                }));
    }


}
