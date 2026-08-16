package org.librewakeword.client;

oneway interface IWakewordDeliveryCallback {
    void acknowledge(String eventId);
}
