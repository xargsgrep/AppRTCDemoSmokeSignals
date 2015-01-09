package org.appspot.apprtc;

import android.util.Log;

import com.github.eventsource.client.EventSource;
import com.github.eventsource.client.EventSourceHandler;
import com.github.eventsource.client.MessageEvent;

import org.appspot.apprtc.util.AsyncHttpURLConnection;
import org.appspot.apprtc.util.AsyncHttpURLConnection.AsyncHttpEvents;
import org.appspot.apprtc.util.LooperExecutor;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.net.URI;
import java.util.concurrent.Executors;

/**
 * Negotiates signaling for chatting with apprtc.appspot.com "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.
 * <p/>
 * <p>To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once room connection is established
 * onConnectedToRoom() callback with room parameters is invoked.
 * Messages to other party (with local Ice candidates and answer SDP) can
 * be sent after WebSocket connection is established.
 */
public class EventSourceRTCClient implements AppRTCClient
{
    private static final String TAG = "ESRTCClient";

    private enum ConnectionState
    {
        NEW, CONNECTED, CLOSED, ERROR
    };

    private enum MessageType
    {
        MESSAGE, BYE
    };

    private final LooperExecutor executor;
    private boolean loopback;
    private boolean initiator;
    private SignalingEvents events;
//    private WebSocketChannelClient wsClient;
    private EventSource eventSource;
    private RoomParametersFetcher fetcher;
    private ConnectionState roomState;
    private String postMessageUrl;
    private String byeMessageUrl;
    private long reconnectionTimeMillis = 5000;

    private String roomName;
    private String uid;
    private String token;
    private String peer;

    public EventSourceRTCClient(SignalingEvents events, String roomName)
    {
        this.events = events;
        this.roomName = roomName;
        executor = new LooperExecutor();
    }

    // --------------------------------------------------------------------
    // AppRTCClient interface implementation.
    // Asynchronously connect to a SmokeSignal room URL, e.g.
    // http://signalcast.herokuapp.com/api/rooms/<room>, retrieve room parameters
    // and connect to EventSource server.
    @Override
    public void connectToRoom(final String url, final boolean loopback)
    {
        postMessageUrl = url;
        executor.requestStart();
        executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                connectToRoomInternal(url, false);
            }
        });
    }

    @Override
    public void disconnectFromRoom()
    {
        executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                disconnectFromRoomInternal();
            }
        });
        executor.requestStop();
    }

    private EventSourceHandler smokeSignalEventSourceHandler = new EventSourceHandler()
    {
        private static final String TAG = "SmokeSignalEventSourceHandler";

        @Override
        public void onConnect() throws Exception
        {
            Log.d(TAG, "onConnect");

            events.onConnectedToRoom(new SignalingParameters(uid, token));
        }

        @Override
        public void onMessage(String event, MessageEvent messageEvent) throws Exception
        {
            Log.d(TAG, "onMessage: " + event + " => " + messageEvent);

            JSONObject data = new JSONObject(messageEvent.data);

            if ("uid".equals(event))
            {
                Log.d(TAG, "uid event");

                String uid = data.getString("uid");
                String token = data.getString("token");

                setUid(uid);
                setToken(token);
            }
            else if ("offer".equals(event))
            {
                Log.d(TAG, "offer event");

                String peer = data.getString("peer");
                setPeer(peer);

                JSONObject offerJson = data.getJSONObject("offer");
                String type = offerJson.getString("type");
                String sdp = offerJson.getString("sdp");

                SessionDescription sessionDescription = new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(type),
                        sdp
                );
                events.onRemoteDescription(sessionDescription);
            }
            else if ("answer".equals(event))
            {
                Log.d(TAG, "answer event");
            }
            else if ("icecandidate".equals(event))
            {
                Log.d(TAG, "icecandidate event");

                String peer = data.getString("peer");
//                setPeer(peer);

                JSONObject candidateJson = data.getJSONObject("candidate");
                String sdpMid = candidateJson.getString("sdpMid");
                int sdpMLineIndex = candidateJson.getInt("sdpMLineIndex");
                String sdp = candidateJson.getString("candidate");

                IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                events.onRemoteIceCandidate(iceCandidate);
            }
            else if ("buddyleft".equals(event))
            {
                Log.d(TAG, "buddyleft event");

                String peer = data.getString("peer");
                setPeer(null);
            }
            else if ("newbuddy".equals(event))
            {
                Log.d(TAG, "newbuddy event");
            }
        }

        @Override
        public void onError(Throwable throwable)
        {
            Log.d(TAG, "onError: " + throwable.getMessage());
        }

        @Override
        public void onClosed(boolean b)
        {
            Log.d(TAG, "onClosed");
        }
    };

    private void setUid(String uid)
    {
        this.uid = uid;
    }

    private void setToken(String token)
    {
        this.token = token;
    }

    private void setPeer(String peer)
    {
        this.peer = peer;
    }

    // Connects to room - function runs on a local looper thread.
    private void connectToRoomInternal(String url, boolean loopback)
    {
        Log.d(TAG, "Connect to room: " + url);

        this.loopback = loopback;
        roomState = ConnectionState.NEW;

        eventSource = new EventSource(
                Executors.newSingleThreadExecutor(),
                reconnectionTimeMillis,
                URI.create(url),
//                new SmokeSignalEventSourceHandler(events, executor)
                smokeSignalEventSourceHandler
        );

        try
        {
            eventSource.connect();
        }
        catch (InterruptedException e)
        {
            reportError("Failed to connect to EventSource: " + e.getMessage());
        }

        // Create WebSocket client.
//        wsClient = new WebSocketChannelClient(executor, this);

        /*
        // Get room parameters.
        fetcher = new RoomParametersFetcher(
                loopback,
                url,
                new RoomParametersFetcher.RoomParametersFetcherEvents()
                {
                    @Override
                    public void onSignalingParametersReady(final SignalingParameters params)
                    {
                        executor.execute(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                signalingParametersReady(params);
                            }
                        });
                    }

                    @Override
                    public void onSignalingParametersError(String description)
                    {
                        reportError(description);
                    }
                }
        );
        */
    }

    // Disconnect from room and send bye messages - runs on a local looper thread.
    private void disconnectFromRoomInternal()
    {
        Log.d(TAG, "Disconnect. Room state: " + roomState);
        if (roomState == ConnectionState.CONNECTED)
        {
            Log.d(TAG, "Closing room.");
            sendPostMessage(MessageType.BYE, byeMessageUrl, "");
        }
        roomState = ConnectionState.CLOSED;
//        if (wsClient != null)
//        {
//            wsClient.disconnect(true);
//        }
        if (eventSource != null)
        {
            eventSource.close();
        }
    }

    // Callback issued when room parameters are extracted. Runs on local looper thread.
    private void signalingParametersReady(final SignalingParameters params)
    {
        Log.d(TAG, "Room connection completed.");
//        if (loopback && (!params.initiator || params.offerSdp != null))
//        {
//            reportError("Loopback room is busy.");
//            return;
//        }
//        if (!loopback && !params.initiator && params.offerSdp == null)
//        {
//            Log.w(TAG, "No offer SDP in room response.");
//        }
        initiator = params.initiator;
//        postMessageUrl = params.roomUrl + "/message/" + params.roomId + "/" + params.clientId;
        postMessageUrl = "/api/rooms/" + roomName;
//        byeMessageUrl = params.roomUrl + "/bye/" + params.roomId + "/" + params.clientId;
        roomState = ConnectionState.CONNECTED;

        // Fire connection and signaling parameters events.
        events.onConnectedToRoom(params);

        // Connect to WebSocket server.
//        wsClient.connect(params.wssUrl, params.wssPostUrl, params.roomId, params.clientId);

        /*
        // For call receiver get sdp offer and ice candidates
        // from room parameters and fire corresponding events.
        if (!params.initiator)
        {
            if (params.offerSdp != null)
            {
                events.onRemoteDescription(params.offerSdp);
            }
            if (params.iceCandidates != null)
            {
                for (IceCandidate iceCandidate : params.iceCandidates)
                {
                    events.onRemoteIceCandidate(iceCandidate);
                }
            }
        }
        */
    }

    // Send local offer SDP to the other participant.
    @Override
    public void sendOfferSdp(final SessionDescription sdp)
    {
        executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                if (roomState != ConnectionState.CONNECTED)
                {
                    reportError("Sending offer SDP in non connected state.");
                    return;
                }
                JSONObject json = new JSONObject();
                jsonPut(json, "sdp", sdp.description);
                jsonPut(json, "type", "offer");
                sendPostMessage(MessageType.MESSAGE, postMessageUrl, json.toString());
                if (loopback)
                {
                    // In loopback mode rename this offer to answer and route it back.
                    SessionDescription sdpAnswer = new SessionDescription(
                            SessionDescription.Type.fromCanonicalForm("answer"),
                            sdp.description);
                    events.onRemoteDescription(sdpAnswer);
                }
            }
        });
    }

    // Send local answer SDP to the other participant.
    @Override
    public void sendAnswerSdp(final SessionDescription sdp)
    {
        executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                if (loopback)
                {
                    Log.e(TAG, "Sending answer in loopback mode.");
                    return;
                }
//                if (wsClient.getState() != WebSocketConnectionState.REGISTERED)
//                {
//                    reportError("Sending answer SDP in non registered state.");
//                    return;
//                }

                JSONObject json = new JSONObject();
                jsonPut(json, "type", "answer");
                jsonPut(json, "peer", peer);
                jsonPut(json, "token", token);

                JSONObject answer = new JSONObject();
                jsonPut(answer, "type", "answer");
                jsonPut(answer, "sdp", sdp.description);

                JSONObject payload = new JSONObject();
                try { payload.put("answer", answer); }
                catch (JSONException e) { e.printStackTrace(); }

                try { json.put("payload", payload); }
                catch (JSONException e) { e.printStackTrace(); }

                sendPostMessage(MessageType.MESSAGE, postMessageUrl, json.toString());

//                wsClient.send(json.toString());
            }
        });
    }

    // Send Ice candidate to the other participant.
    @Override
    public void sendLocalIceCandidate(final IceCandidate candidate)
    {
        executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                JSONObject json = new JSONObject();
                jsonPut(json, "type", "icecandidate");
                jsonPut(json, "peer", peer);
                jsonPut(json, "token", token);

                JSONObject candidateJson = new JSONObject();
                jsonPut(candidateJson, "candidate", candidate.sdp);
                jsonPut(candidateJson, "sdpMid", candidate.sdpMid);
                jsonPut(candidateJson, "sdpMLineIndex", candidate.sdpMLineIndex);

                JSONObject payload = new JSONObject();
                try { payload.put("candidate", candidateJson); }
                catch (JSONException e) { e.printStackTrace(); }

                try { json.put("payload", payload); }
                catch (JSONException e) { e.printStackTrace(); }

//                JSONObject json = new JSONObject();
//                jsonPut(json, "type", "candidate");
//                jsonPut(json, "label", candidate.sdpMLineIndex);
//                jsonPut(json, "id", candidate.sdpMid);
//                jsonPut(json, "candidate", candidate.sdp);

                if (initiator)
                {
                    // Call initiator sends ice candidates to GAE server.
                    if (roomState != ConnectionState.CONNECTED)
                    {
                        reportError("Sending ICE candidate in non connected state.");
                        return;
                    }
                    sendPostMessage(MessageType.MESSAGE, postMessageUrl, json.toString());

                    if (loopback)
                    {
                        events.onRemoteIceCandidate(candidate);
                    }
                }
                else
                {
                    sendPostMessage(MessageType.MESSAGE, postMessageUrl, json.toString());
                    // Call receiver sends ice candidates to websocket server.
//                    if (wsClient.getState() != WebSocketConnectionState.REGISTERED)
//                    {
//                        reportError("Sending ICE candidate in non registered state.");
//                        return;
//                    }
//                    wsClient.send(json.toString());
                }
            }
        });
    }

    // --------------------------------------------------------------------
    // Helper functions.
    private void reportError(final String errorMessage)
    {
        Log.e(TAG, errorMessage);
        executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                if (roomState != ConnectionState.ERROR)
                {
                    roomState = ConnectionState.ERROR;
                    events.onChannelError(errorMessage);
                }
            }
        });
    }

    // Put a |key|->|value| mapping in |json|.
    private static void jsonPut(JSONObject json, String key, Object value)
    {
        try
        {
            json.put(key, value);
        }
        catch (JSONException e)
        {
            throw new RuntimeException(e);
        }
    }

    // Send SDP or ICE candidate to a room server.
    private void sendPostMessage(final MessageType messageType, final String url, final String message)
    {
        if (messageType == MessageType.BYE)
        {
            Log.d(TAG, "C->GAE: " + url);
        } else
        {
            Log.d(TAG, "C->GAE: " + message);
        }
        AsyncHttpURLConnection httpConnection = new AsyncHttpURLConnection("POST", url, message, new AsyncHttpEvents()
        {
            @Override
            public void OnHttpError(String errorMessage)
            {
                reportError("GAE POST error: " + errorMessage);
            }

            @Override
            public void OnHttpComplete(String response)
            {
                if (messageType == MessageType.MESSAGE)
                {
//                    try
//                    {
//                        JSONObject roomJson = new JSONObject(response);
//                        String result = roomJson.getString("result");
//                        if (!result.equals("SUCCESS"))
                        if (!response.equals("ok"))
                        {
                            reportError("GAE POST error: " + response);
                        }
//                    }
//                    catch (JSONException e)
//                    {
//                        reportError("GAE POST JSON error: " + e.toString());
//                    }
                }
            }
        });
        httpConnection.send();
    }
}
