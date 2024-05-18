package com.github.vincentrussell.dns;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Test;
import org.springframework.util.SocketUtils;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class DnsServerTest {

    @Test
    public void realResponse() throws IOException {
        int port = SocketUtils.findAvailableUdpPort();
        try (DnsServer server = new DnsServer().addExternalDnsServer("8.8.8.8").setPort(port).startServer()) {
            Record[] records = getRecords(port, new Lookup("www.google.com"), false);
            assertNotNull(records);
            assertEquals(Name.fromString("www.google.com."), server.getCachedDnsEntries().keySet().iterator().next());
        }
    }

    @Test
    public void realResponseWithExternalServer() throws IOException {
        int port = SocketUtils.findAvailableUdpPort();
        try (DnsServer server = new DnsServer().setPort(port).addExternalDnsServer("8.8.8.8").startServer()) {
            Record[] records = getRecords(port, new Lookup("www.google.com"), false);
            assertNotNull(records);
        }
    }

    @Test
    public void notFound() throws IOException {
        int port = SocketUtils.findAvailableUdpPort();
        try (DnsServer server = new DnsServer().setPort(port).startServer()) {
            Record[] records = getRecords(port, new Lookup("www.google.xxx"), false);
            assertNull(records);
        }
    }

    @Test
    public void addManualEntry() throws IOException {
        int port = SocketUtils.findAvailableUdpPort();
        try (DnsServer server = new DnsServer().setPort(port).startServer()) {
            server.addManualDnsEntry("www.google.xxx", "192.168.12.1");
            server.addManualDnsEntry("www.google.xyz", "192.168.12.99");
            Record[] records = getRecords(port, new Lookup("www.google.xxx"), false);
            assertEquals("192.168.12.1", ((ARecord) records[0]).getAddress().getHostAddress());

            records = getRecords(port, new Lookup("www.google.xyz"), false);
            assertEquals("192.168.12.99", ((ARecord) records[0]).getAddress().getHostAddress());

            assertEquals(new TreeSet<>(Sets.newHashSet(Name.fromString("www.google.xyz."),
                            Name.fromString("www.google.xxx."))),
                    new TreeSet<>(server.getManualDnsEntries().keySet()));
        }
    }


    @Test
    public void multipleResponsesForSameQuery() throws IOException {
        int port = SocketUtils.findAvailableUdpPort();
        try (DnsServer server = new DnsServer().setPort(port).startServer()) {
            server.addManualDnsEntry("www.google.xxx", "192.168.12.1");
            server.addManualDnsEntry("www.google.xxx", "192.168.12.2");
            server.addManualDnsEntry("www.google.xxx", "192.168.12.3");
            server.addManualDnsEntry("www.google.xxx", "192.168.12.4");
            Record[] records = getRecords(port, new Lookup("www.google.xxx"), false);
            assertEquals(4, records.length);
            assertEquals("192.168.12.1", ((ARecord) records[0]).getAddress().getHostAddress());
            assertEquals("192.168.12.2", ((ARecord) records[1]).getAddress().getHostAddress());
            assertEquals("192.168.12.3", ((ARecord) records[2]).getAddress().getHostAddress());
            assertEquals("192.168.12.4", ((ARecord) records[3]).getAddress().getHostAddress());
        }
    }


    @Test
    public void addManualEntryUseTcpAndUdp() throws IOException {
        int port = SocketUtils.findAvailableUdpPort();
        try (DnsServer server = new DnsServer().setPort(port).startServer()) {
            server.addManualDnsEntry("www.google.xxx", "192.168.12.1");
            server.addManualDnsEntry("www.google.xyz", "192.168.12.99");
            Record[] records = getRecords(port, new Lookup("www.google.xxx"), true);
            assertEquals("192.168.12.1", ((ARecord) records[0]).getAddress().getHostAddress());

            records = getRecords(port, new Lookup("www.google.xxx"), false);
            assertEquals("192.168.12.1", ((ARecord) records[0]).getAddress().getHostAddress());

            records = getRecords(port, new Lookup("www.google.xyz"), true);
            assertEquals("192.168.12.99", ((ARecord) records[0]).getAddress().getHostAddress());

            records = getRecords(port, new Lookup("www.google.xyz"), false);
            assertEquals("192.168.12.99", ((ARecord) records[0]).getAddress().getHostAddress());

            assertEquals(new TreeSet<>(Sets.newHashSet(Name.fromString("www.google.xyz."),
                            Name.fromString("www.google.xxx."))),
                    new TreeSet<>(server.getManualDnsEntries().keySet()));
        }
    }


    @Test
    public void addManualEntryMultiThreaded() throws IOException, InterruptedException {
        int port = SocketUtils.findAvailableUdpPort();
        try (DnsServer server = new DnsServer().setPort(port).startServer()) {



            int concurrentThreads = Runtime.getRuntime().availableProcessors();

            ExecutorService executorService = Executors.newFixedThreadPool(concurrentThreads);

            List<Callable<Record[]>> callableList = new ArrayList<>();

            for (int i = 0; i < concurrentThreads; i++) {
                String ip = "192.168.12.1" + i;
                String hostname = "www" + i + ".google.xyz";
                server.addManualDnsEntry(hostname, ip);

                callableList.add(new Callable() {
                    @Override
                    public Record[] call() throws Exception {
                        try {
                            Record[] records = getRecords(port, new Lookup(hostname), false);
                            assertEquals(ip, ((ARecord) records[0]).getAddress().getHostAddress());
                            return records;
                        } catch (UnknownHostException e) {
                            throw new RuntimeException(e);
                        } catch (TextParseException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }

            List<Future<Record[]>> futures = executorService.invokeAll(callableList);

            executorService.shutdown();
            executorService.awaitTermination(1, TimeUnit.MINUTES);

            List<Record> records = Lists.transform(Lists.newArrayList(futures), new Function<Future<Record[]>, Record>() {
                @Override
                public @Nullable Record apply(@Nullable Future<Record[]> future) {
                    try {
                        return future.get()[0];
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } catch (ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            assertEquals(concurrentThreads, records.size());
        }
    }

    private Record[] getRecords(int port, Lookup lookup, boolean tcp) throws UnknownHostException, TextParseException {
        SimpleResolver resolver = new SimpleResolver(new InetSocketAddress(InetAddress.getLocalHost(), port));
        resolver.setTimeout(Duration.ofSeconds(1));
        resolver.setTCP(tcp);

        lookup.setResolver(resolver);
        lookup.setCache(null);

        Record[] records = lookup.run();
        return records;
    }

}
