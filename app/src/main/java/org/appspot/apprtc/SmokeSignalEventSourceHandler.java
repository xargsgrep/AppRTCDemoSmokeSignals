package org.appspot.apprtc;

import android.util.Log;

import com.github.eventsource.client.EventSourceHandler;
import com.github.eventsource.client.MessageEvent;

import org.appspot.apprtc.util.LooperExecutor;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

public class SmokeSignalEventSourceHandler implements EventSourceHandler
{
    private static final String TAG = "SmokeSignalEventSourceHandler";

    private AppRTCClient.SignalingEvents events;
    private final LooperExecutor executor;
    private Runnable onConnectionRunnable;

    public SmokeSignalEventSourceHandler(AppRTCClient.SignalingEvents events, LooperExecutor executor)
    {
        this.events = events;
        this.executor = executor;
//        this.onConnectionRunnable = onConnectionRunnable;
    }

    @Override
    public void onConnect() throws Exception
    {
        Log.d(TAG, "onConnect");
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
        }
        else if ("offer".equals(event))
        {
            Log.d(TAG, "offer event");

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
}
