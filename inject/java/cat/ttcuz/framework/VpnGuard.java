package cat.ttcuz.framework;

import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A VPN TikTok does not see. Every way its Java code asks whether the network is a VPN is answered as for the
 * network under it (dexpatch: these calls of TikTok's come here):
 * the VPN transport (hasTransport 4) is not there and the network is "not a VPN" (hasCapability 15); an old-style
 * network type VPN (17) reads as Wi-Fi; the tunnel interfaces (tun, ppp, tap, wg, ipsec...) are left out of the list of
 * interfaces and their names read as wlan0; and the printed descriptions lose the word. A proxy is left as it is:
 * TikTok routes its traffic by it, and hiding it would send the traffic around a VPN that works as a proxy.
 * With the region left as it is, every answer is the real one. Native code TikTok runs on its own is not reached.
 */
public final class VpnGuard {
    private VpnGuard() {}

    /** On together with the region change: a changed country and a visible VPN would not agree. */
    static boolean on() { return Ttcuz.active(); }

    private static final int TRANSPORT_VPN = 4, NOT_VPN = 15, TYPE_VPN = 17, TYPE_WIFI = 1;

    /** A tunnel's interface name: tun0, ppp0, tap0, wg0, ipsec0, utun1, l2tp... */
    static boolean tunnel(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("tap") || n.startsWith("wg")
                || n.startsWith("ipsec") || n.startsWith("utun") || n.startsWith("l2tp") || n.startsWith("pptp");
    }

    public static boolean hasTransport(NetworkCapabilities caps, int transport) {
        if (caps == null) return false;
        if (transport == TRANSPORT_VPN && on()) return false;
        return caps.hasTransport(transport);
    }

    public static boolean hasCapability(NetworkCapabilities caps, int capability) {
        if (caps == null) return false;
        if (capability == NOT_VPN && on()) return true;
        return caps.hasCapability(capability);
    }

    public static String toString(NetworkCapabilities caps) {
        return caps == null ? "null" : plain(caps.toString());
    }

    public static int getType(NetworkInfo info) {
        int type = info.getType();
        return type == TYPE_VPN && on() ? TYPE_WIFI : type;
    }

    public static String getTypeName(NetworkInfo info) {
        String name = info.getTypeName();
        return on() && "VPN".equalsIgnoreCase(name) ? "WIFI" : name;
    }

    public static Enumeration<NetworkInterface> getNetworkInterfaces() throws java.net.SocketException {
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        if (all == null || !on()) return all;
        List<NetworkInterface> kept = new ArrayList<>();
        while (all.hasMoreElements()) {
            NetworkInterface item = all.nextElement();
            if (item != null && !tunnel(item.getName())) kept.add(item);
        }
        return Collections.enumeration(kept);
    }

    public static String getName(NetworkInterface item) {
        String name = item.getName();
        return on() && tunnel(name) ? "wlan0" : name;
    }

    public static String getInterfaceName(LinkProperties link) {
        String name = link.getInterfaceName();
        return on() && tunnel(name) ? "wlan0" : name;
    }

    public static String toString(LinkProperties link) {
        return link == null ? "null" : plain(link.toString());
    }

    /** A printed network without the VPN in it: no VPN transport, the tunnel's name as wlan0. */
    static String plain(String text) {
        if (text == null || !on()) return text;
        return text.replace("|VPN", "").replace("VPN|", "").replace("Transports: VPN", "Transports: WIFI")
                .replaceAll("(?i)\\b(tun|ppp|tap|wg|ipsec|utun)\\d+\\b", "wlan0");
    }
}
