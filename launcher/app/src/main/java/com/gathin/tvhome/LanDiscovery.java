package com.gathin.tvhome;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Lets a phone on the home network find the TV without typing an address: the TV announces "_tvhome._tcp" (mDNS) with the
 * port of the control page. Also finds the TV's own address on the network, for the QR.
 */
final class LanDiscovery {
    private static final String TAG = "LanDiscovery";
    private static NsdManager manager;
    private static NsdManager.RegistrationListener listener;

    private LanDiscovery() {
    }

    static synchronized void start(Context ctx, int port) {
        stop();
        try {
            manager = ctx.getApplicationContext().getSystemService(NsdManager.class);
            if (manager == null) return;
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("TV-" + android.os.Build.MODEL.replaceAll("[^A-Za-z0-9]", ""));
            info.setServiceType("_tvhome._tcp");
            info.setPort(port);
            listener = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "announce failed: " + errorCode);
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                }
            };
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException e) {
            Log.w(TAG, "announce failed: " + e);
            listener = null;
        }
    }

    static synchronized void stop() {
        if (manager != null && listener != null) {
            try {
                manager.unregisterService(listener);
            } catch (RuntimeException ignored) {
            }
        }
        listener = null;
    }

    /** The TV's IPv4 address on the home network (192.168.x.x, 10.x.x.x, 172.16-31.x.x), or null. */
    static String address() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (java.net.SocketException ignored) {
        }
        return null;
    }
}
