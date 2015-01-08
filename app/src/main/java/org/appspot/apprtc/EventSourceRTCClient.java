package org.appspot.apprtc;

import android.util.Log;

import com.github.eventsource.client.EventSource;

import org.appspot.apprtc.WebSocketChannelClient.WebSocketConnectionState;
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

    public EventSourceRTCClient(SignalingEvents events)
    {
        this.events = events;
        executor = new LooperExecutor();
    }

    // --------------------------------------------------------------------
    // AppRTCClient interface implementation.
    // Asynchronously connect to a SmokeSignal room URL, e.g.
    // http://signalcast.herokuapp.com/api/<room>, retrieve room parameters
    // and connect to EventSource server.
    @Override
    public void connectToRoom(final String url, final boolean loopback)
    {
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

    // Connects to room - function runs on a local looper thread.
    private void connectToRoomInternal(String url, boolean loopback)
    {
        Log.d(TAG, "Connect to room: " + url);
        this.loopback = loopback;
        roomState = ConnectionState.NEW;

        eventSource = new EventSource(
                Executors.newSingleThreadExecutor(),
                reconnectionTimeMillis,
                URI.create(""),
                new SmokeSignalEventSourceHandler()
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

    // Callback issued when room parameters are extracted. Runs on local
    // looper thread.
    private void signalingParametersReady(final SignalingParameters params)
    {
        Log.d(TAG, "Room connection completed.");
        if (loopback && (!params.initiator || params.offerSdp != null))
        {
            reportError("Loopback room is busy.");
            return;
        }
        if (!loopback && !params.initiator && params.offerSdp == null)
        {
            Log.w(TAG, "No offer SDP in room response.");
        }
        initiator = params.initiator;
        postMessageUrl = params.roomUrl + "/message/"
                + params.roomId + "/" + params.clientId;
        byeMessageUrl = params.roomUrl + "/bye/"
                + params.roomId + "/" + params.clientId;
        roomState = ConnectionState.CONNECTED;

        // Fire connection and signaling parameters events.
        events.onConnectedToRoom(params);

        // Connect to WebSocket server.
//        wsClient.connect(params.wssUrl, params.wssPostUrl, params.roomId, params.clientId);

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
                jsonPut(json, "sdp", sdp.description);
                jsonPut(json, "type", "answer");
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
                jsonPut(json, "type", "candidate");
                jsonPut(json, "label", candidate.sdpMLineIndex);
                jsonPut(json, "id", candidate.sdpMid);
                jsonPut(json, "candidate", candidate.sdp);
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
                } else
                {
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
    private void sendPostMessage(
            final MessageType messageType, final String url, final String message)
    {
        if (messageType == MessageType.BYE)
        {
            Log.d(TAG, "C->GAE: " + url);
        } else
        {
            Log.d(TAG, "C->GAE: " + message);
        }
        AsyncHttpURLConnection httpConnection = new AsyncHttpURLConnection(
                "POST", url, message, new AsyncHttpEvents()
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
                    try
                    {
                        JSONObject roomJson = new JSONObject(response);
                        String result = roomJson.getString("result");
                        if (!result.equals("SUCCESS"))
                        {
                            reportError("GAE POST error: " + result);
                        }
                    }
                    catch (JSONException e)
                    {
                        reportError("GAE POST JSON error: " + e.toString());
                    }
                }
            }
        });
        httpConnection.send();
    }
}
