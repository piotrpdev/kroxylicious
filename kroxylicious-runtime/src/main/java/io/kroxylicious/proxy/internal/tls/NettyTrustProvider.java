/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.tls;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import io.kroxylicious.proxy.config.secret.FilePassword;
import io.kroxylicious.proxy.config.secret.PasswordProvider;
import io.kroxylicious.proxy.config.tls.InsecureTls;
import io.kroxylicious.proxy.config.tls.PlatformTrustProvider;
import io.kroxylicious.proxy.config.tls.ServerOptions;
import io.kroxylicious.proxy.config.tls.TlsClientAuth;
import io.kroxylicious.proxy.config.tls.TrustProvider;
import io.kroxylicious.proxy.config.tls.TrustProviderVisitor;
import io.kroxylicious.proxy.config.tls.TrustStore;
import io.kroxylicious.proxy.security.FilePermissionValidator;

import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class NettyTrustProvider {

    public static final String HTTPS_HOSTNAME_VERIFICATION = "HTTPS";
    private final TrustProvider trustProvider;

    public NettyTrustProvider(TrustProvider trustProvider) {
        this.trustProvider = trustProvider;
    }

    public SslContextBuilder apply(SslContextBuilder builder) {
        return trustProvider.accept(new TrustProviderVisitor<>() {
            @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Paths are provided by the operator via Kroxylicious configuration and may reside anywhere on the filesystem.")
            @Override
            public SslContextBuilder visit(TrustStore trustStore) {
                try {
                    FilePermissionValidator.validate(Path.of(trustStore.storeFile()), "truststore");
                    validatePasswordProvider(trustStore.storePasswordProvider());

                    enableHostnameVerification();
                    enableClientAuth(trustStore);
                    if (trustStore.isPemType()) {
                        return builder.trustManager(new File(trustStore.storeFile()));
                    }
                    else {
                        try (var is = new FileInputStream(trustStore.storeFile())) {
                            var password = Optional.ofNullable(trustStore.storePasswordProvider()).map(PasswordProvider::getProvidedPassword).map(String::toCharArray)
                                    .orElse(null);
                            var keyStore = KeyStore.getInstance(trustStore.getType());
                            keyStore.load(is, password);

                            var trustManagerFactory = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                            trustManagerFactory.init(keyStore);

                            return builder.trustManager(trustManagerFactory);
                        }
                    }
                }
                catch (Exception e) {
                    throw new SslContextBuildException("Error building SSLContext for TrustStore: " + trustStore, e);
                }
            }

            private void enableClientAuth(TrustStore trustStore) {
                ClientAuth clientAuth = Optional.ofNullable(trustStore.trustOptions())
                        .filter(ServerOptions.class::isInstance)
                        .map(ServerOptions.class::cast)
                        .map(ServerOptions::clientAuth)
                        .map(NettyTrustProvider::toNettyClientAuth)
                        .orElse(ClientAuth.REQUIRE);
                builder.clientAuth(clientAuth);
            }

            @Override
            public SslContextBuilder visit(InsecureTls insecureTls) {
                try {
                    if (insecureTls.insecure()) {
                        disableHostnameVerification();
                        return builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                    }
                    else {
                        enableHostnameVerification();
                        return builder;
                    }
                }
                catch (Exception e) {
                    throw new SslContextBuildException("Error building SSLContext for InsecureTls: " + insecureTls, e);
                }
            }

            @Override
            public SslContextBuilder visit(PlatformTrustProvider platformTrustProviderTls) {
                enableHostnameVerification();
                return builder;
            }

            private void enableHostnameVerification() {
                setEndpointAlgorithm(HTTPS_HOSTNAME_VERIFICATION);
            }

            private void disableHostnameVerification() {
                setEndpointAlgorithm(null);
            }

            private void setEndpointAlgorithm(@Nullable String httpsHostnameVerification) {
                builder.endpointIdentificationAlgorithm(httpsHostnameVerification);
            }
        });
    }

    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Paths are provided by the operator via Kroxylicious configuration and may reside anywhere on the filesystem.")
    private void validatePasswordProvider(@Nullable PasswordProvider provider) {
        if (provider instanceof FilePassword fp) {
            FilePermissionValidator.validate(java.nio.file.Path.of(fp.passwordFile()), "password file");
        }
    }

    private static ClientAuth toNettyClientAuth(TlsClientAuth clientAuth) {
        return switch (clientAuth) {
            case REQUIRED -> ClientAuth.REQUIRE;
            case REQUESTED -> ClientAuth.OPTIONAL;
            case NONE -> ClientAuth.NONE;
        };
    }

}
