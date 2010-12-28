/*
 * Licensed under Apache License, Version 2.0 or LGPL 2.1, at your option.
 * --
 *
 * Copyright 2010 Rene Treffer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * --
 *
 * Copyright (C) 2010 Rene Treffer
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301 USA
 */

package com.googlecode.asmack.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.util.Log;

import com.googlecode.asmack.Attribute;
import com.googlecode.asmack.Stanza;
import com.googlecode.asmack.connection.IXmppTransportService;

/**
 * Basic transport service client.
 */
public class AsmackClient implements PacketListener {

    /**
     * The stanza intent name.
     */
    private final static String STANZA_INTENT_ACTION =
                            "com.googlecode.asmack.intent.XMPP.STANZA.RECEIVE";

    /**
     * The logging tag, AsmackClient.
     */
    private static final String TAG = AsmackClient.class.getSimpleName();

    /**
     * The bound transport service.
     */
    private IXmppTransportService xmppTransportService;

    /**
     * The service connection of the transport service.
     */
    private TransportServiceConnection serviceConnection;

    /**
     * Internal hashmap for reply id to callback mapping.
     */
    private HashMap<String, Callback> replyMap =
                                    new HashMap<String, Callback>();

    /**
     * SortedSet for time-to-live tracking and callback pruning.
     */
    private TreeSet<Callback> replyTtl = new TreeSet<Callback>();

    /**
     * Lock for reply callback changes, to avoid concurrent changes.
     */
    private ReentrantLock replyLock = new ReentrantLock();

    /**
     * Current atomic id counter.
     */
    private final AtomicInteger idStatus = new AtomicInteger();

    /**
     * ID prefix for id generation.
     */
    private final String idPrefix;

    /**
     * List of packet listeners.
     */
    private CopyOnWriteArrayList<PacketListener> listeners =
                                new CopyOnWriteArrayList<PacketListener>();

    /**
     * List of service event listeners.
     */
    private CopyOnWriteArrayList<TransportServiceBindListener> serviceListener =
                    new CopyOnWriteArrayList<TransportServiceBindListener>();

    private AsmackBroadcastReceiver stanzaReceiver;

    /**
     * <p>Create a new asmack service client.</p>
     * <p>The arguments are used to generate the packet ids.</p>
     * @param idPrefix The id prefix for stanza sending.
     */
    public AsmackClient(String idPrefix) {
        this.idPrefix = idPrefix;
        xmppTransportService = null;
        idStatus.set(new Random().nextInt());
        Log.d(TAG, "instanciate");
    }

    /**
     * Open and start the transport service client.
     * @param context The application context used for service binding.
     */
    public void open(Context context, int id) {
        Intent serviceIntent =
            new Intent(IXmppTransportService.class.getCanonicalName());
        context.startService(serviceIntent);
        serviceConnection = new TransportServiceConnection(this);
        context.bindService(
            serviceIntent,
            serviceConnection,
            Context.BIND_NOT_FOREGROUND | Context.BIND_AUTO_CREATE
        );
        stanzaReceiver = new AsmackBroadcastReceiver(id, this);
        context.registerReceiver(
            stanzaReceiver,
            new IntentFilter(STANZA_INTENT_ACTION)
        );
    }

    /**
     * Add a packet listener to the list of permanent packet receivers.
     * @param listener The smack packet listener.
     */
    public synchronized void registerListener(PacketListener listener) {
        listeners.add(listener);
    }

    /**
     * Register a conditional packet listener.
     * @param filter The packet filter.
     * @param listener The actual listener.
     */
    public void registerListener(
        PacketFilter filter,
        PacketListener listener
    ) {
        if (filter != null) {
            registerListener(new PacketFilterListener(filter, listener));
        } else {
            registerListener(listener);
        }
    }

    /**
     * Remove a listener from the listener chain. A NullPointerException will
     * be thrown if the listener is null.
     * @param filter The packet filter. May be null.
     * @param listener The actual listener.
     * @return True on success.
     */
    public synchronized boolean removeListener(
        PacketFilter filter,
        PacketListener listener
    ) {
        if (listener == null) {
            throw new NullPointerException("can't remove listener 'null'");
        }
        if (filter == null && listeners.remove(listener)) {
            return true;
        }
        if (filter == null) {
            return false;
        }
        for (PacketListener l: listeners) {
            if (!(l instanceof PacketFilterListener)) {
                continue;
            }
            PacketFilterListener filterListener = (PacketFilterListener) l;
            if (filterListener.getFilter() == filter &&
                filterListener.getListener() == listener) {
                listeners.remove(filterListener);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a listener from the global listener list.
     * @param listener The listener to be removed.
     * @return True on success.
     */
    public boolean removeListener(PacketListener listener) {
        return removeListener(null, listener);
    }

    /**
     * Send a single stanza via the remote service.
     * @param stanza The stanza that should be send.
     * @return The id String.
     * @throws RemoteException In case of a service breakdown.
     */
    public String send(Stanza stanza) throws RemoteException {
        return sendWithCallback(stanza, null, 0);
    }

    /**
     * Sends a packet over the wire, generating and setting a new id.
     * @param packet The smack packet to send.
     * @return The id String.
     * @throws RemoteException In case of a service breakdown.
     */
    public String send(Packet packet, String via) throws RemoteException {
        return sendWithCallback(packet, via, null, 0l);
    }

    /**
     * Sends a packet over the wire, generating and setting a new id.
     * @param packet The smack packet to send.
     * @param ttl Time to live.
     * @return The id String.
     * @throws RemoteException In case of a service breakdown.
     */
    public String sendWithCallback(
        Packet packet,
        String via,
        PacketListener callback,
        long ttl
    ) throws RemoteException {
        String id = generateId();
        packet.setPacketID(id);
        String name = "message";
        if (packet instanceof IQ) {
            name = "iq";
        }
        if (packet instanceof Presence) {
            name = "presence";
        }
        ArrayList<Attribute> attributes = new ArrayList<Attribute>();
        attributes.add(new Attribute("id", "", id));
        Stanza stanza = new Stanza(
            name,
            packet.getXmlns(),
            via,
            packet.toXML(),
            attributes
        );
        sendWithCallback(stanza, callback, ttl);
        return id;
    }

    /**
     * Send a single stanza, registering a callback for the auto-generated id.
     * The time to live is the guaranteed minimum time that the callback will
     * survive.
     * @param stanza The stanza to send.
     * @param callback The callback on rply.
     * @param ttl The time to live of the callback, in milliseconds.
     * @return The stanza id.
     * @throws RemoteException In case of a remote service breakdown.
     */
    public String sendWithCallback(
        Stanza stanza,
        PacketListener callback,
        long ttl
    ) throws RemoteException {
        String id = getStanzaId(stanza);
        if (callback != null && ttl > 0) {
            try {
                replyLock.lock();
                Callback cb = new Callback(
                        System.currentTimeMillis() + ttl,
                        id,
                        callback
                );
                replyMap.put(id, cb);
                this.replyTtl.add(cb);
            } finally {
                replyLock.unlock();
            }
        }
        xmppTransportService.send(stanza);
        return id;
    }

    /**
     * Close the client.
     * @param context The context that should be used for unbinding.
     */
    public void close(Context context) {
        context.unbindService(serviceConnection);
        context.unregisterReceiver(stanzaReceiver);
    }

    /**
     * Helper to geot or generate a stanza id.
     * @param stanza The stanza.
     * @return The stanza id.
     */
    private String getStanzaId(Stanza stanza) {
        Attribute idAttribute = stanza.getAttribute("id");
        if (idAttribute == null) {
            idAttribute = new Attribute("id", generateId(), null);
            stanza.addAttribute(idAttribute);
        }
        return idAttribute.getValue();
    }

    /**
     * Generate a new stanza id.
     * @return A new stanza id.
     */
    private String generateId() {
        return idPrefix + "-" +
            Integer.toHexString(idStatus.getAndIncrement());
    }

    /**
     * Check if the service is still alive.
     * @param context The context used for the check.
     * @return True on success.
     */
    public boolean checkService(Context context) {
        IXmppTransportService service = xmppTransportService;
        if (service == null) {
            return false;
        }
        return service.asBinder().pingBinder();
    }

    /**
     * Purge stalled callbacks based on time to live constrains.
     */
    public void purgeCallback() {
        if (replyTtl.size() == 0) {
            return;
        }
        long time = System.currentTimeMillis();
        Callback first = replyTtl.first();
        while (first.getTTL() > time) {
            replyTtl.remove(first);
            replyMap.remove(first.getId());
            if (replyTtl.size() == 0) {
                return;
            }
            first = replyTtl.first();
        }
    }

    /**
     * Add a new transport bind listener.
     * @param listener The transport bind listener.
     */
    public synchronized void addTransportServiceBindListener(
        TransportServiceBindListener listener
    ) {
        serviceListener.add(listener);
    }

    /**
     * Remove a transport bind listener.
     * @param listener The transport bind listener.
     * @return True on success.
     */
    public synchronized boolean removeTransportServiceBindListener(
            TransportServiceBindListener listener
    ) {
        return serviceListener.remove(listener);
    }

    /**
     * Called when a service connects, informs all listeners.
     * @param service The service that connected.
     */
    public void onTrasportServiceConnect(IXmppTransportService service) {
        xmppTransportService = service;
        for (TransportServiceBindListener listener: serviceListener) {
            listener.onTrasportServiceConnect(service);
        }
    }

    /**
     * Called whenever the service fails.
     * @param service The service that failed.
     */
    public void onTrasportServiceDisconnect(IXmppTransportService service) {
        for (TransportServiceBindListener listener: serviceListener) {
            listener.onTrasportServiceDisconnect(service);
        }
    }

    /**
     * Process a packet, call all listeners and remove packet specific
     * callbacks.
     * @param packet The smack packet.
     */
    @Override
    public void processPacket(Packet packet) {
        String id = packet.getPacketID();
        if (id != null) {
            Callback callback = null;
            try {
                replyLock.lock();
                callback = replyMap.remove(id);
                purgeCallback();
                if (callback != null) {
                    replyTtl.remove(callback);
                }
            } finally {
                replyLock.unlock();
            }
            if (callback != null) {
                callback.getCallback().processPacket(null);
            }
        }
        for (PacketListener listener: listeners) {
            try {
                listener.processPacket(packet);
            } catch (Exception e) {
                Log.e(TAG,
                    "PacketListener throws an exception. "
                     + listener,
                     e);
            }
        }
    }

}
