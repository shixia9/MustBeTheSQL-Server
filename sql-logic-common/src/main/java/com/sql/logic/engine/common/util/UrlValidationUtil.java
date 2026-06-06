package com.sql.logic.engine.common.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * URL validation utility to prevent SSRF (Server-Side Request Forgery) attacks.
 * Validates that URLs point to allowed hosts and not to internal/private networks.
 */
public class UrlValidationUtil {

    private UrlValidationUtil() {}

    /**
     * Validate a base URL for SSRF prevention.
     * Ensures the URL uses HTTP/HTTPS and does not resolve to a private/internal IP.
     *
     * @param url the URL to validate
     * @throws IllegalArgumentException if the URL is invalid or points to a private network
     */
    public static void validateBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return; // null/empty baseUrl is allowed (uses default)
        }

        String trimmed = url.trim();

        // Must start with http:// or https://
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }

        try {
            URI uri = new URI(trimmed);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("Base URL must contain a valid host");
            }

            // Resolve the hostname and check against private IP ranges
            InetAddress address = InetAddress.getByName(host);
            if (isPrivateAddress(address)) {
                throw new IllegalArgumentException("Base URL must not point to a private/internal network address");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve host in base URL: " + e.getMessage());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid base URL: " + e.getMessage());
        }
    }

    /**
     * Check if an InetAddress points to a private/reserved network.
     */
    private static boolean isPrivateAddress(InetAddress address) {
        return address.isLoopbackAddress()     // 127.x.x.x, ::1
                || address.isSiteLocalAddress()  // 10.x.x.x, 172.16-31.x.x, 192.168.x.x
                || address.isLinkLocalAddress()  // 169.254.x.x
                || address.isAnyLocalAddress();  // 0.0.0.0
    }
}