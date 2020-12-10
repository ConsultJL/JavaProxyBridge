import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;

public class Proxy implements Runnable {
    /**
     * ArrayList of threads that are currently running and servicing requests.
     * This list is required in order to join all threads on closing of server
     */
    static ArrayList<Thread> servicingThreads;
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public static void main(String[] args) {
        Proxy myProxy = new Proxy(8085);
        myProxy.listen();
    }

    public Proxy(int port) {
        // Create array list to hold servicing threads
        servicingThreads = new ArrayList<>();

        // Start dynamic manager on a separate thread.
        new Thread(this).start();	// Starts overriden run() method at bottom

        try {
            // Create the Server Socket for the Proxy
            serverSocket = new ServerSocket(port);

            // Set the timeout
            serverSocket.setSoTimeout(100000);	// debug
            System.out.println("Waiting for client on port " + serverSocket.getLocalPort() + "..");
            running = true;
        }

        // Catch exceptions associated with opening socket
        catch (SocketException se) {
            System.out.println("Socket Exception when connecting to client");
            se.printStackTrace();
        }
        catch (SocketTimeoutException ste) {
            System.out.println("Timeout occured while connecting to client");
        }
        catch (IOException io) {
            System.out.println("IO exception when connecting to client");
        }
    }

    public void listen(){
        while(running) {
            try {
                // serverSocket.accpet() Blocks until a connection is made
                Socket socket = serverSocket.accept();

                // Create new Thread and pass it Runnable RequestHandler
                Thread thread = new Thread(new RequestHandler(socket));

                // Key a reference to each thread so they can be joined later if necessary
                servicingThreads.add(thread);

                thread.start();
            } catch (SocketException e) {
                // Socket exception is triggered by management system to shut down the proxy
                System.out.println("Server closed");
            } catch (SocketTimeoutException e) {
                System.out.println("Timeout");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void closeServer(){
        System.out.println("\nClosing Server..");
        running = false;

        try{
            // Close all servicing threads
            for(Thread thread : servicingThreads){
                if(thread.isAlive()){
                    System.out.print("Waiting on "+  thread.getId()+" to close..");
                    thread.join();
                    System.out.println(" closed");
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Close Server Socket
        try{
            System.out.println("Terminating Connection");
            serverSocket.close();
        } catch (Exception e) {
            System.out.println("Exception closing proxy's server socket");
            e.printStackTrace();
        }

    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);

        String command;
        while(running){
            System.out.println("Enter new site to block, or type \"blocked\" to see blocked sites, \"cached\" to see cached sites, or \"close\" to close server.");
            command = scanner.nextLine();
            if(command.toLowerCase().equals("close")){
                running = false;
                closeServer();
            }
        }
        scanner.close();
    }
}
