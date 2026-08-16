package org.librewakeword.client;

import org.librewakeword.client.WakewordAvailability;
import org.librewakeword.client.WakewordEvent;
import org.librewakeword.client.IWakewordDeliveryCallback;

interface IWakewordConsumer {
    oneway void beginMicrophoneHandoff(
        in WakewordEvent event,
        in IWakewordDeliveryCallback callback
    );
    oneway void completeMicrophoneHandoff(
        in WakewordEvent event,
        in IWakewordDeliveryCallback callback
    );
    oneway void onWakewordEvent(in WakewordEvent event, in IWakewordDeliveryCallback callback);
    oneway void onAvailabilityChanged(in WakewordAvailability availability);
}
