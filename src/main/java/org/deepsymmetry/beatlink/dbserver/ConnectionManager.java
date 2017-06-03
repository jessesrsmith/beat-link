package org.deepsymmetry.beatlink.dbserver;

import org.deepsymmetry.beatlink.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Manges connections to dbserver ports on the players, offering sessions that can be used to perform transactions,
 * and allowing the connections to close when there are no active sessions.
 *
 * @author James Elliott
 */
public class ConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class.getName());

    /**
     * An interface for all the kinds of activities that need a connection to the dbserver for, so we can keep track
     * of how many sessions are in effect, clean up after them, and know when the client is idle and can be closed.
     *
     * @param <T> the type returned by the activity
     */
    public interface ClientTask<T> {
        T useClient(Client client) throws Exception;
    }

    /**
     * Obtain a dbserver client session that can be used to perform some task, call that task with the client,
     * then release the client.
     *
     * @param targetPlayer the player number whose dbserver we wish to communicate with
     * @param task the activity that will be performed with exclusive access to a dbserver connection
     * @param description a short description of the task being performed for error reporting if it fails,
     *                    should be a verb phrase like "requesting track metadata"
     * @param <T> the type that will be returned by the task to be performed
     *
     * @return the value returned by the completed task
     *
     * @throws IOException if there is a problem communicating
     * @throws Exception from the underlying {@code task}, if any
     */
    public static <T> T invokeWithClientSession(int targetPlayer, ClientTask<T> task, String description)
            throws Exception {
        // TODO: look for an existing client

        final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getLatestAnnouncementFrom(targetPlayer);
        final int dbServerPort = getPlayerDBServerPort(targetPlayer);
        if (deviceAnnouncement == null || dbServerPort < 0) {
            throw new IllegalStateException("Player " + targetPlayer + " could not be found " + description);
        }

        final byte posingAsPlayerNumber = (byte) chooseAskingPlayerNumber(targetPlayer);

        Socket socket = null;
        try {
            InetSocketAddress address = new InetSocketAddress(deviceAnnouncement.getAddress(), dbServerPort);
            socket = new Socket();
            socket.connect(address, socketTimeout);
            socket.setSoTimeout(socketTimeout);
            return task.useClient(new Client(socket, targetPlayer, posingAsPlayerNumber));
        } finally {
            // TODO: release the client to the pool rather than just closing it
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Problem closing socket for " + description, e);
                }
            }
        }
    }

    /**
     * Keeps track of the database server ports of all the players we have seen on the network.
     */
    private static final Map<Integer, Integer> dbServerPorts = new HashMap<Integer, Integer>();

    /**
     * Look up the database server port reported by a given player. You should not use this port directly; instead
     * ask this class for a session to use while you communicate with the database.
     *
     * @param player the player number of interest
     *
     * @return the port number on which its database server is running, or -1 if unknown
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized int getPlayerDBServerPort(int player) {
        Integer result = dbServerPorts.get(player);
        if (result == null) {
            return -1;
        }
        return result;
    }

    /**
     * Our announcement listener watches for devices to appear on the network so we can ask them for their database
     * server port, and when they disappear discards all information about them.
     */
    private static final DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    requestPlayerDBServerPort(announcement);
                }
            }).start();
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            setPlayerDBServerPort(announcement.getNumber(), -1);
        }
    };


    /**
     * Record the database server port reported by a player.
     *
     * @param player the player number whose server port has been determined.
     * @param port the port number on which the player's database server is running.
     */
    private static synchronized void setPlayerDBServerPort(int player, int port) {
        dbServerPorts.put(player, port);
    }

    /**
     * The port on which we can request information about a player, including the port on which its database server
     * is running.
     */
    private static final int DB_SERVER_QUERY_PORT = 12523;

    private static final byte[] DB_SERVER_QUERY_PACKET = {
            0x00, 0x00, 0x00, 0x0f,
            0x52, 0x65, 0x6d, 0x6f, 0x74, 0x65, 0x44, 0x42, 0x53, 0x65, 0x72, 0x76, 0x65, 0x72,  // RemoteDBServer
            0x00
    };

    /**
     * Query a player to determine the port on which its database server is running.
     *
     * @param announcement the device announcement with which we detected a new player on the network.
     */
    private static void requestPlayerDBServerPort(DeviceAnnouncement announcement) {
        Socket socket = null;
        try {
            InetSocketAddress address = new InetSocketAddress(announcement.getAddress(), DB_SERVER_QUERY_PORT);
            socket = new Socket();
            socket.connect(address, socketTimeout);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            socket.setSoTimeout(socketTimeout);
            os.write(DB_SERVER_QUERY_PACKET);
            byte[] response = readResponseWithExpectedSize(is, 2, "database server port query packet");
            if (response.length == 2) {
                setPlayerDBServerPort(announcement.getNumber(), (int)Util.bytesToNumber(response, 0, 2));
            }
        } catch (java.net.ConnectException ce) {
            logger.info("Player " + announcement.getNumber() +
                    " doesn't answer rekordbox port queries, connection refused. Won't attempt to request metadata.");
        } catch (Exception e) {
            logger.warn("Problem requesting database server port number", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Problem closing database server port request socket", e);
                }
            }
        }
    }

    /**
     * The default value we will use for timeouts on opening and reading from sockets.
     */
    @SuppressWarnings("WeakerAccess")
    public static final int DEFAULT_SOCKET_TIMEOUT = 10000;

    /**
     * The number of milliseconds after which an attempt to open or read from a socket will fail.
     */
    private static int socketTimeout = DEFAULT_SOCKET_TIMEOUT;

    /**
     * Set how long we will wait for a socket to connect or for a read operation to complete.
     * Adjust this if your players or network require it.
     *
     * @param timeout after how many milliseconds will an attempt to open or read from a socket fail
     */
    public static void setSocketTimeout(int timeout) {
        socketTimeout = timeout;
    }

    /**
     * Check how long we will wait for a socket to connect or for a read operation to complete.
     * Adjust this if your players or network require it.
     *
     * @return the number of milliseconds after which an attempt to open or read from a socket will fail
     */
    public static int getSocketTimeout() {
        return socketTimeout;
    }

    /**
     * Receive some bytes from the player we are requesting metadata from.
     *
     * @param is the input stream associated with the player metadata socket.
     * @return the bytes read.
     *
     * @throws IOException if there is a problem reading the response
     */
    private static byte[] receiveBytes(InputStream is) throws IOException {
        byte[] buffer = new byte[8192];
        int len = (is.read(buffer));
        if (len < 1) {
            throw new IOException("receiveBytes read " + len + " bytes.");
        }
        return Arrays.copyOf(buffer, len);
    }

    /**
     * Receive an expected number of bytes from the player, logging a warning if we get a different number of them.
     *
     * @param is the input stream associated with the player metadata socket.
     * @param size the number of bytes we expect to receive.
     * @param description the type of response being processed, for use in the warning message.
     * @return the bytes read.
     *
     * @throws IOException if there is a problem reading the response.
     */
    @SuppressWarnings("SameParameterValue")
    private static byte[] readResponseWithExpectedSize(InputStream is, int size, String description) throws IOException {
        byte[] result = receiveBytes(is);
        if (result.length != size) {
            logger.warn("Expected " + size + " bytes while reading " + description + " response, received " + result.length);
        }
        return result;
    }

    /**
     * Finds a valid  player number that is currently visible but which is different from the one specified, so it can
     * be used as the source player for a query being sent to the specified one. If the virtual CDJ is running on an
     * acceptable player number (which must be 1-4 to request metadata from an actual CDJ, but can be anything if we
     * are talking to rekordbox), uses that, since it will always be safe. Otherwise, tries to borrow the player number
     * of another actual CDJ on the network, but we can't do that if the player we want to impersonate has mounted
     * a track from the player that we want to talk to.
     *
     * @param targetPlayer the player to which a metadata query is being sent
     *
     * @return some other currently active player number, ideally not a real player, but sometimes we have to
     *
     * @throws IllegalStateException if there is no other player number available to use
     */
    private static int chooseAskingPlayerNumber(int targetPlayer) {
        final int fakeDevice = VirtualCdj.getDeviceNumber();
        if ((targetPlayer > 15) || (fakeDevice >= 1 && fakeDevice <= 4)) {
            return fakeDevice;
        }

        for (DeviceAnnouncement candidate : DeviceFinder.currentDevices()) {
            final int realDevice = candidate.getNumber();
            if (realDevice != targetPlayer && realDevice >= 1 && realDevice <= 4) {
                final DeviceUpdate lastUpdate =  VirtualCdj.getLatestStatusFor(realDevice);
                if (lastUpdate != null && lastUpdate instanceof CdjStatus &&
                        ((CdjStatus)lastUpdate).getTrackSourcePlayer() != targetPlayer) {
                    return candidate.getNumber();
                }
            }
        }
        throw new IllegalStateException("No player number available to query player " + targetPlayer +
                ". If such a player is present on the network, it must be using Link to play a track from " +
                "our target player, so we can't steal its channel number.");
    }

    /**
     * Keep track of whether we are running
     */
    private static boolean running = false;

    /**
     * Check whether we are currently running.
     *
     * @return true if we are offering shared dbserver sessions
     */
    @SuppressWarnings("WeakerAccess")
    public static synchronized boolean isRunning() {
        return running;
    }

    /**
     * Start offering shared dbserver sessions.
     *
     * @throws SocketException if there is a problem opening connections
     */
    public static synchronized void start() throws SocketException {
        if (!running) {
            DeviceFinder.start();
            DeviceFinder.addDeviceAnnouncementListener(announcementListener);
            for (DeviceAnnouncement device: DeviceFinder.currentDevices()) {
                requestPlayerDBServerPort(device);
            }

            running = true;
        }
    }

    /**
     * Stop offering shared dbserver sessions.
     */
    public static synchronized  void stop() {
        if (running) {
            running = false;
            DeviceFinder.removeDeviceAnnouncementListener(announcementListener);
            dbServerPorts.clear();
            // TODO: Clean up anything else!
        }
    }

}
