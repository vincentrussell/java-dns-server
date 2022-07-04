package com.github.vincentrussell.dns;

import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"checkstyle:HiddenField", "checkstyle:DesignForExtension",
        "checkstyle:MagicNumber"})
public class DnsServer implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DnsServer.class);

    public static final int DEFAULT_DNS_SERVER_PORT = 53;
    private long cacheExpirationDuration = 1;
    private TimeUnit cacheExpirationUnit = TimeUnit.HOURS;
    private int udpServerRequestThreadPoolSize = 50;
    private Thread udpThread = null;
    private ExecutorService executorService = null;

    private InetAddress bindAddress = null;

    private DnsServerListener dnsServerListener = new DefaultDnsServerListener();
    private volatile boolean running = false;
    private static final int UDP_SIZE = 512;
    private int port = DEFAULT_DNS_SERVER_PORT;
    private final List<Resolver> externalDnsResolvers = new ArrayList<>();
    private LoadingCache<Name, Record[]> dnsCache;
    private Map<Name, Set<Record>> manualDnsEntries = new ConcurrentHashMap<>();
    private long defaultResponseTTl = 86400;
    private int remoteDnsRetryCount = 5;
    private long remoteDnsTimeoutInSeconds = 3;
    private Thread tcpThread;
    private int tcpServerMaxLengthIncomingConnectionsQueue = 128;

    public DnsServer() {
    }
    public DnsServer setCacheExpirationDuration(final long cacheExpirationDuration,
                                                final TimeUnit cacheExpirationUnit) {
        this.cacheExpirationDuration = cacheExpirationDuration;
        this.cacheExpirationUnit = cacheExpirationUnit;
        return this;
    }

    public DnsServer setUdpServerRequestThreadPoolSize(final int udpServerRequestThreadPoolSize) {
        this.udpServerRequestThreadPoolSize = udpServerRequestThreadPoolSize;
        return this;
    }

    public DnsServer setTcpServerMaxLengthIncomingConnectionsQueue(
            final int tcpServerMaxLengthIncomingConnectionsQueue) {
        this.tcpServerMaxLengthIncomingConnectionsQueue =
                tcpServerMaxLengthIncomingConnectionsQueue;
        return this;
    }

    public DnsServer setDefaultResponseTTl(final long defaultResponseTTl) {
        this.defaultResponseTTl = defaultResponseTTl;
        return this;
    }

    public DnsServer setRemoteDnsRetryCount(final int remoteDnsRetryCount) {
        this.remoteDnsRetryCount = remoteDnsRetryCount;
        return this;
    }

    public DnsServer setRemoteDnsTimeoutInSeconds(final long remoteDnsTimeoutInSeconds) {
        this.remoteDnsTimeoutInSeconds = remoteDnsTimeoutInSeconds;
        return this;
    }

    public DnsServer setDnsServerListener(final DnsServerListener dnsServerListener) {
        this.dnsServerListener = dnsServerListener;
        return this;
    }

    public DnsServer setPort(final int port) {
        this.port = port;
        return this;
    }

    public DnsServer setBindAddress(final String hostname) throws UnknownHostException {
        return setBindAddress(InetAddress.getByName(hostname));
    }
    public DnsServer setBindAddress(final InetAddress bindAddress) {
        this.bindAddress = bindAddress;
        return this;
    }

    public DnsServer startServer() {
        dnsCache = CacheBuilder.newBuilder()
                .expireAfterAccess(cacheExpirationDuration, cacheExpirationUnit)
                .build(
                        new CacheLoader<Name, Record[]>() {
                            public Record[] load(final Name name) {
                                LOGGER.info("looking up in remote dns server: {}", name);
                                Record[] run = performDnsLookupInRemoteDnsServers(name);
                                return run;
                            }
                        }
                );

        running = true;
        executorService = Executors.newFixedThreadPool(udpServerRequestThreadPoolSize);
        udpThread = new Thread(() -> {
                    try {
                        listenOnUdpSocket();
                    } catch (Throwable ex) {
                        stop();
                        throw new RuntimeException(ex);
                    } finally {
                        dnsServerListener.listenThreadExited();
                    }
            });
        udpThread.start();

        tcpThread = new Thread(() -> {
            try {
                listenOnTcpSocket();
            } catch (Throwable ex) {
                stop();
                throw new RuntimeException(ex);
            } finally {
                dnsServerListener.listenThreadExited();
            }
        });
        tcpThread.start();
        return this;
    }
    public Map<Name, Set<Record>> getManualDnsEntries() {
        return Collections.unmodifiableMap(manualDnsEntries);
    }
    public Map<Name, Set<Record>> getCachedDnsEntries() {
        return Collections.unmodifiableMap(Maps.transformValues(dnsCache.asMap(),
                (Function<Record[], Set<Record>>) records -> Sets.newHashSet(records)));
    }

    public Record[] performDnsLookupInRemoteDnsServers(final Name name) {
        ExtendedResolver extendedResolver = new ExtendedResolver(externalDnsResolvers);
        extendedResolver.setTimeout(Duration.ofSeconds(remoteDnsTimeoutInSeconds));
        extendedResolver.setRetries(remoteDnsRetryCount);
        Lookup lookup = new Lookup(name);
        lookup.setResolver(extendedResolver);
        lookup.setCache(null);
        Record[] results = lookup.run();
        if (results != null) {
            for (Record record : results) {
                LOGGER.info("found record {} for name {}", record, name);
            }
        }
        return results;
    }

    private void stop() {
        running = false;
        udpThread.interrupt();
        udpThread = null;
        tcpThread.interrupt();
        tcpThread = null;
        executorService.shutdownNow();
        executorService = null;
    }

    private void listenOnUdpSocket() throws IOException {
        DatagramSocket socket = new DatagramSocket(port, bindAddress != null
                ? bindAddress : InetAddress.getLocalHost());
        while (running) {
            final byte[] bytes = new byte[UDP_SIZE];
            // Read the request
            final DatagramPacket datagramPacket = new DatagramPacket(bytes, UDP_SIZE);
            socket.receive(datagramPacket);
            executorService.submit(() -> {
                try {
                    processDatagramPacket(socket, bytes, datagramPacket);
                } catch (IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            });
        }
    }

    private void listenOnTcpSocket() throws IOException {
        ServerSocket sock = new ServerSocket(port,
                tcpServerMaxLengthIncomingConnectionsQueue, bindAddress != null
                ? bindAddress : InetAddress.getLocalHost());
        while (running) {
            final Socket s = sock.accept();
            executorService.submit(() -> {
                try {
                    processTcpRequest(s);
                } catch (IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            });
        }
    }

    private void processTcpRequest(final Socket socket) throws IOException {
        try (InputStream is = socket.getInputStream();
             DataInputStream dataIn = new DataInputStream(is);
             DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream())) {
            final int inLength = dataIn.readUnsignedShort();
            final byte[] bytes = new byte[inLength];
            dataIn.readFully(bytes);

            final Message request = new Message(bytes);
            final Message response = buildResponse(request);
            final byte[] resp = response.toWire();

            dataOut.writeShort(resp.length);
            dataOut.write(resp);
        }
    }

    private void processDatagramPacket(final DatagramSocket socket, final byte[] bytes,
                                       final DatagramPacket datagramPacket) throws IOException {
        // Build the response
        Message request = new Message(bytes);
        Message response = buildResponse(request);

        byte[] resp = response.toWire();
        DatagramPacket outdp = new DatagramPacket(resp, resp.length,
                datagramPacket.getAddress(), datagramPacket.getPort());
        socket.send(outdp);
    }

    private Message buildResponse(final Message request) {
        Message response = new Message(request.getHeader().getID());
        response.addRecord(request.getQuestion(), Section.QUESTION);

        try {
            final Set<Record> manualRecords = manualDnsEntries.get(request.getQuestion().getName());

            if (manualRecords != null && !manualRecords.isEmpty()) {
                for (Record record : manualRecords) {
                    response.addRecord(record, Section.ANSWER);
                }
            } else {
                Record[] records = dnsCache.get(request.getQuestion().getName());
                for (Record record : records) {
                    response.addRecord(record, Section.ANSWER);
                }
            }
        } catch (CacheLoader.InvalidCacheLoadException e) {
            LOGGER.info("{} not found in cache or manual records error message{}",
                    request.getQuestion().getName(),  e.getMessage());
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        return response;
    }

    @Override
    public void close() throws IOException {
        stop();
    }

    public DnsServer addExternalDnsServer(final String host) throws UnknownHostException {
       return addExternalDnsServer(InetAddress.getByName(host));
    }

    public DnsServer addExternalDnsServer(
            final InetAddress inetSocketAddress) throws UnknownHostException {
        return addExternalDnsServer(
                new InetSocketAddress(inetSocketAddress, DEFAULT_DNS_SERVER_PORT));
    }

    public DnsServer addExternalDnsServer(
            final InetSocketAddress inetSocketAddress) throws UnknownHostException {
        externalDnsResolvers.add(new SimpleResolver(inetSocketAddress));
        return this;
    }

    public DnsServer addManualDnsEntry(final Name name,
                                       final InetAddress inetAddress) throws IOException {
        manualDnsEntries.computeIfAbsent(name, name1 ->
                new HashSet<>()).add(Record.fromString(name, Type.A, DClass.IN, defaultResponseTTl,
                inetAddress.getHostAddress(), name));
        return this;
    }

    public DnsServer addManualDnsEntry(final String hostname, final String ip) throws IOException {
        return addManualDnsEntry(toName(hostname), InetAddress.getByName(ip));
    }

    private Name toName(final String hostname) throws TextParseException {
        return Name.fromString(hostname.endsWith(".") ? hostname : hostname + ".");
    }
    public Set<Record> removeManualDnsEntry(final String hostname) throws TextParseException {
        return removeManualDnsEntry(toName(hostname));
    }
    public void clearManualDnsEntries() {
        manualDnsEntries.clear();
    }

    public Set<Record> removeManualDnsEntry(final Name name) {
        return manualDnsEntries.remove(name);
    }
}
