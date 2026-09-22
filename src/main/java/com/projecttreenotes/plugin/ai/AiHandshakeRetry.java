package com.projecttreenotes.plugin.ai;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;

/** Only retry remote handshake termination, before an HTTP request can be sent. */
final class AiHandshakeRetry {
    @FunctionalInterface
    interface Request<T> {
        T send() throws IOException, InterruptedException;
    }

    static <T> T execute(Request<T> request) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                return request.send();
            } catch (IOException failure) {
                if (attempt >= 2 || !isRemoteTermination(failure)) throw failure;
                Thread.sleep(500L * (attempt + 1));
            }
        }
    }

    private static boolean isRemoteTermination(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean remoteTermination = false;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof CertificateException) return false;
            if (cause instanceof SSLHandshakeException
                    && "Remote host terminated the handshake".equals(cause.getMessage())) {
                remoteTermination = true;
            }
        }
        return remoteTermination;
    }
}
