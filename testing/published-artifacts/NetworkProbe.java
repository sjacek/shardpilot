import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Reserved documentation address: the isolated network must reject this deliberate attempt. */
class NetworkProbe {
    public static void main(String[] arguments) throws IOException {
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress("198.51.100.1", 443), 500);
            } catch (IOException expected) {
                System.out.println("positive control: connection blocked");
                return;
            }
        }
        throw new IOException("Isolated network unexpectedly allowed the positive control");
    }
}
