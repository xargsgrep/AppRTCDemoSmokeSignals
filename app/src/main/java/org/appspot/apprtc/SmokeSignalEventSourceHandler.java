package org.appspot.apprtc;

import com.github.eventsource.client.EventSourceHandler;
import com.github.eventsource.client.MessageEvent;

public class SmokeSignalEventSourceHandler implements EventSourceHandler
{
    @Override
    public void onConnect() throws Exception
    {
        System.out.println("connected");
    }

    @Override
    public void onMessage(String s, MessageEvent messageEvent) throws Exception
    {
        System.out.println(s + " => " + messageEvent);
    }

    @Override
    public void onError(Throwable throwable)
    {
        System.out.println("error: " + throwable.getMessage());
    }

    @Override
    public void onClosed(boolean b)
    {
        System.out.println("closed");
    }
}
