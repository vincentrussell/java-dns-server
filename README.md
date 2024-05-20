# java-dns-server [![Maven Central](https://img.shields.io/maven-central/v/com.github.vincentrussell/java-dns-server.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.github.vincentrussell%22%20AND%20a:%22java-dns-server%22) [![Build Status](https://travis-ci.org/vincentrussell/java-dns-server.svg?branch=master)](https://travis-ci.org/vincentrussell/java-dns-server)

java-dns-server is a dns server that can be used for testing purposes that is written in java.

## Maven

Add a dependency to `com.github.vincentrussell:java-dns-server:1.2`. 

```
<dependency>
   <groupId>com.github.vincentrussell</groupId>
   <artifactId>java-dns-server</artifactId>
   <version>1.2/version>
</dependency>
```

## Requirements
- JDK 1.8 or higher
- Apache Maven 3.1.0 or higher

## Running it from Java

```
 try (DnsServer server = new DnsServer().setPort(port).startServer()) {
            server.addManualDnsEntry("www.google.xxx", "192.168.12.1");
            server.addManualDnsEntry("www.google.xyz", "192.168.12.99");
}
```


# Change Log

## [1.2](https://github.com/vincentrussell/java-dns-server/tree/java-dns-server-1.1) (2024-05-19)

**Improvements:**

- updating dns library and improving logging.

## [1.1](https://github.com/vincentrussell/java-dns-server/tree/java-dns-server-1.1) (2022-07-04)

**Improvements:**

- adding TCP support to DNS Server

## [1.0](https://github.com/vincentrussell/java-dns-server/tree/java-dns-server-1.0) (2022-06-26)

**Improvements:**

- initial release