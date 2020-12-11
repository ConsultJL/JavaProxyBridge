import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.*;
import java.net.Proxy;
import java.util.Random;
import javax.imageio.ImageIO;

public class RequestHandler implements Runnable {

    /**
     * Socket connected to client passed by Proxy server
     */
    Socket clientSocket;

    /**
     * Read data client sends to proxy
     */
    BufferedReader proxyToClientBr;

    /**
     * Send data from proxy to client
     */
    BufferedWriter proxyToClientBw;


    /**
     * Creates a RequestHandler object capable of servicing HTTP(S) GET requests
     *
     * @param clientSocket socket connected to the client
     */
    public RequestHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
        try {
            this.clientSocket.setSoTimeout(20000);
            proxyToClientBr = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            proxyToClientBw = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * Reads and examines the requestString and calls the appropriate method based
     * on the request type.
     */
    @Override
    public void run() {

        // Get Request from client
        String requestString;
        try {
            requestString = proxyToClientBr.readLine();
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Error reading request from client");
            return;
        }

        // Parse out URL

        System.out.println("Request Received " + requestString);
        // Get the Request type
        String request = requestString.substring(0, requestString.indexOf(' '));

        // remove request type and space
        String urlString;
        urlString = requestString.substring(requestString.indexOf(' ') + 1);

        // Remove everything past next space
        urlString = urlString.substring(0, urlString.indexOf(' '));

        // Prepend http:// if necessary to create correct URL
        if (!urlString.startsWith("http")) {
            String temp = "http://";
            urlString = temp + urlString;
        }

        // Check request type
        if (request.equals("CONNECT")) {
            System.out.println("HTTPS Request for : " + urlString + "\n");
            handleHTTPSRequest(urlString);
        } else {
            // Check if we have a cached copy
            System.out.println("HTTP GET for : " + urlString + "\n");
            sendNonCachedToClient(urlString);
        }
    }


    /**
     * Sends the contents of the file specified by the urlString to the client
     *
     * @param urlString URL of the file requested
     */
    private void sendNonCachedToClient(String urlString) {
        try {
            // Compute a logical file name as per schema
            // This allows the files on stored on disk to resemble that of the URL it was taken from
            int fileExtensionIndex = urlString.lastIndexOf(".");
            String fileExtension;

            // Get the type of file
            fileExtension = urlString.substring(fileExtensionIndex);

            // Get the initial file name
            String fileName = urlString.substring(0, fileExtensionIndex);


            // Trim off http://www. as no need for it in file name
            fileName = fileName.substring(fileName.indexOf('.') + 1);

            // Remove any illegal characters from file name
            fileName = fileName.replace("/", "__");
            fileName = fileName.replace('.', '_');

            // Trailing / result in index.html of that directory being fetched
            if (fileExtension.contains("/")) {
                fileExtension = fileExtension.replace("/", "__");
                fileExtension = fileExtension.replace('.', '_');
                fileExtension += ".html";
            }

            fileName = fileName + fileExtension;

            // Check if file is an image
            if ((fileExtension.contains(".png")) || fileExtension.contains(".jpg") ||
                    fileExtension.contains(".jpeg") || fileExtension.contains(".gif")) {
                // Create the URL
                URL remoteURL = new URL(urlString);
                BufferedImage image = ImageIO.read(remoteURL);

                if (image != null) {
                    // Send response code to client
                    String line = "HTTP/1.0 200 OK\n" +
                            "Proxy-agent: ProxyBridge/1.0\n" +
                            "\r\n";
                    proxyToClientBw.write(line);
                    proxyToClientBw.flush();

                    // Send them the image data
                    ImageIO.write(image, fileExtension.substring(1), clientSocket.getOutputStream());

                    // No image received from remote server
                } else {
                    System.out.println("Sending 404 to client as image wasn't received from server"
                            + fileName);
                    String error = "HTTP/1.0 404 NOT FOUND\n" +
                            "Proxy-agent: ProxyBridge/1.0\n" +
                            "\r\n";
                    proxyToClientBw.write(error);
                    proxyToClientBw.flush();
                    return;
                }
            } else {
                int proxyLevel = 0;
                Proxy[] proxies = getProxyList();
                System.out.println("Using proxy: " + proxies[proxyLevel].toString());
                URL remoteURL = new URL(urlString);
                HttpURLConnection proxyToServerCon = (HttpURLConnection) remoteURL.openConnection(proxies[0]);
                proxyToServerCon.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded");
                proxyToServerCon.setRequestProperty("Content-Language", "en-US");
                proxyToServerCon.setRequestProperty("User-Agent", getRandomUserAgent());
                proxyToServerCon.setUseCaches(false);
                proxyToServerCon.setDoOutput(true);


                boolean connFailed = false;
                BufferedReader proxyToServerBR = null;
                while (!connFailed) {
                    try {
                        proxyToServerBR = new BufferedReader(new InputStreamReader(proxyToServerCon.getInputStream()));
                    } catch (IOException e) {
                        int responseCode = proxyToServerCon.getResponseCode();
                        if (responseCode != 200) {
                            System.out.println("Proxy Level: " + proxyLevel);
                            System.out.println("Retries Left: " + (proxies.length - 1));
                            if (proxyLevel == proxies.length - 1) {
                                System.out.println("No more proxies left to try");
                                proxyToClientBw.write("Connection: close");
                                proxyToClientBw.close();
                                return;
                            }
                            System.out.println("Retrying with next proxy");
                            proxyToServerCon = (HttpURLConnection) remoteURL.openConnection(proxies[proxyLevel + 1]);
                            proxyToServerCon.setRequestProperty("Content-Type",
                                    "application/x-www-form-urlencoded");
                            proxyToServerCon.setRequestProperty("Content-Language", "en-US");
                            proxyToServerCon.setRequestProperty("User-Agent", getRandomUserAgent());
                            proxyToServerCon.setUseCaches(false);
                            proxyToServerCon.setDoOutput(true);
                            proxyLevel++;
                        }
                    }
                    if (proxyToServerCon.getResponseCode() == 200) {
                        connFailed = true;
                        break;
                    }
                }

                int total = 0;
                StringBuilder requestData = new StringBuilder();
                String line = "HTTP/1.0 200 OK\n" +
                        "Proxy-agent: ProxyBridge/1.0\n" +
                        "\r\n";
                proxyToClientBw.write(line);
                total += line.length();
                requestData.append(line);

                // Read from input stream between proxy and remote server
                try {
                    while ((line = proxyToServerBR.readLine()) != null) {
                        proxyToClientBw.write(line);
                        total += line.length();
                        requestData.append(line);
                    }
                } catch (IOException e) {
                    proxyToServerBR.close();
                    proxyToClientBw.close();
                    proxyToServerCon.disconnect();
                }

                proxyToClientBw.flush();

                System.out.println("Total Recv: " + total);
                System.out.println("Data Received:");
                System.out.println(requestData);

                if (proxyToServerBR != null) {
                    proxyToServerBR.close();
                }
            }
            if (proxyToClientBw != null) {
                proxyToClientBw.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Proxy[] getProxyList() {
        Proxy[] proxies = new Proxy[4];
        proxies[0] = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy-main-entry", 8085));
        proxies[1] = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy-main-entry", 8086));
        proxies[2] = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy-main-entry", 8087));
        proxies[3] = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("proxy-main-entry", 8089));

        return proxies;
    }

    public String getRandomUserAgent() {
        String[] userAgents = new String[3];

        userAgents[0] = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:79.0) Gecko/20100101 Firefox/79.0";
        userAgents[1] = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/85.0.4183.83 Safari/537.36";
        userAgents[2] = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.5 Safari/605.1.15";

        Random rand = new Random();
        return userAgents[rand.nextInt(3)];
    }

    /**
     * Handles HTTPS requests between client and remote server
     *
     * @param urlString desired file to be transmitted over https
     */
    private void handleHTTPSRequest(String urlString) {
        long count = urlString.chars().filter(ch -> ch == ':').count();
        String[] pieces;
        String url;
        if (count == 1) {
            pieces = urlString.split(":");
            url = pieces[0];
        } else {
            url = urlString.substring(7);
            pieces = url.split(":");
            url = pieces[0];
        }
        int port = Integer.parseInt(pieces[1]);

        try {
            int proxyLevel = 0;
            Proxy[] proxies = getProxyList();
            System.out.println("Using proxy: " + proxies[proxyLevel].toString());
            InetAddress address = InetAddress.getByName(url);
            System.out.println("Connecting to "+address.toString());
            Socket proxyToServerSocket = new Socket(proxies[0]);
            SocketAddress socketAddress = new InetSocketAddress(address, port);
            try {
                proxyToServerSocket.connect(socketAddress);
            } catch (IOException e) {
                e.printStackTrace();
            }

            proxyToServerSocket.setSoTimeout(5000);

            String line = "HTTP/1.0 200 Connection established\r\n" +
                    "Proxy-Agent: ProxyBridge/1.0\r\n" +
                    "\r\n";

            proxyToClientBw.write(line);
            proxyToClientBw.flush();
            BufferedWriter proxyToServerBW = new BufferedWriter(new OutputStreamWriter(proxyToServerSocket.getOutputStream()));
            BufferedReader proxyToServerBR = new BufferedReader(new InputStreamReader(proxyToServerSocket.getInputStream()));
            ClientToServerHttpsTransmit clientToServerHttps =
                    new ClientToServerHttpsTransmit(clientSocket.getInputStream(), proxyToServerSocket.getOutputStream());

            System.out.println("Starting new thread to handle client server communication.");
            Thread httpsClientToServer = new Thread(clientToServerHttps);
            httpsClientToServer.start();

            try {
                byte[] buffer = new byte[4096];
                int read;
                int total = 0;
                do {
                    read = proxyToServerSocket.getInputStream().read(buffer);
                    if (read > 0) {
                        total += read;
                        clientSocket.getOutputStream().write(buffer, 0, read);
                        if (proxyToServerSocket.getInputStream().available() < 1) {
                            clientSocket.getOutputStream().flush();
                        }
                    }
                } while (read >= 0);
                System.out.println("Total Recv: " + total);
            } catch (SocketTimeoutException ignored) {

            } catch (IOException e) {
                e.printStackTrace();
            }

            if (proxyToServerSocket != null) {
                proxyToServerSocket.close();
            }
            if (proxyToServerBR != null) {
                proxyToServerBR.close();
            }
            if (proxyToServerBW != null) {
                proxyToServerBW.close();
            }
            if (proxyToClientBw != null) {
                proxyToClientBw.close();
            }
        } catch (SocketTimeoutException e) {
            String line = "HTTP/1.0 504 Timeout Occurred after 10s\n" +
                    "User-Agent: ProxyBridge/1.0\n" +
                    "\r\n";
            try {
                proxyToClientBw.write(line);
                proxyToClientBw.flush();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        } catch (Exception e) {
            System.out.println("Error on HTTPS : " + urlString);
            e.printStackTrace();
        }
    }

    /**
     * Listen to data from client and transmits it to server.
     * This is done on a separate thread as must be done
     * asynchronously to reading data from server and transmitting
     * that data to the client.
     */
    static class ClientToServerHttpsTransmit implements Runnable {
        InputStream proxyToClientIS;
        OutputStream proxyToServerOS;

        /**
         * Creates Object to Listen to Client and Transmit that data to the server
         *
         * @param proxyToClientIS Stream that proxy uses to receive data from client
         * @param proxyToServerOS Stream that proxy uses to transmit data to remote server
         */
        public ClientToServerHttpsTransmit(InputStream proxyToClientIS, OutputStream proxyToServerOS) {
            this.proxyToClientIS = proxyToClientIS;
            this.proxyToServerOS = proxyToServerOS;
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[4096];
                int read;
                int total = 0;
                do {
                    read = proxyToClientIS.read(buffer);
                    System.out.println(buffer);
                    if (read > 0) {
                        total += read;
                        proxyToServerOS.write(buffer, 0, read);
                        if (proxyToClientIS.available() < 1) {
                            proxyToServerOS.flush();
                        }
                    }
                } while (read >= 0);
                System.out.println("Total Sent: " + total);
            } catch (SocketException se) {
                System.out.println("Socket sad");
//                se.printStackTrace();
            } catch (IOException e) {
                System.out.println("Proxy to client HTTPS read timed out");
//                e.printStackTrace();
            }
        }
    }
}




