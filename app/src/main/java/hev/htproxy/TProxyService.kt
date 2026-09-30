package hev.htproxy

/**
 * JNI surface of hev-socks5-tunnel. The native library registers its methods against
 * exactly this class (hev/htproxy/TProxyService) with exactly these signatures:
 *   TProxyStartService (Ljava/lang/String;I)Z
 *   TProxyStopService  ()Z
 *   TProxyIsRunning    ()Z
 *   TProxyGetStats     ()[J   -> [txPackets, txBytes, rxPackets, rxBytes]
 * Any mismatch makes JNI_OnLoad fail and the process abort.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    @JvmStatic external fun TProxyGetStats(): LongArray
}
