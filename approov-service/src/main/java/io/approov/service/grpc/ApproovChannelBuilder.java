//
// MIT License
//
// Copyright (c) 2016-present, Critical Blue Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
// Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
// COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.grpc;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;

import io.grpc.ChannelCredentials;
import io.grpc.ClientInterceptor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.internal.GrpcUtil;
import io.grpc.okhttp.OkHttpChannelBuilder;

public class ApproovChannelBuilder extends ManagedChannelBuilder<ApproovChannelBuilder> {

    OkHttpChannelBuilder channelBuilder;

    public ApproovChannelBuilder(String target) {
        channelBuilder = OkHttpChannelBuilder.forTarget(target);
        HostnameVerifier pinningHostnameVerifier =
                new ApproovPinningHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        channelBuilder.hostnameVerifier(pinningHostnameVerifier);
    }

    public ApproovChannelBuilder(String target, ChannelCredentials creds) {
        channelBuilder = OkHttpChannelBuilder.forTarget(target, creds);
        HostnameVerifier pinningHostnameVerifier =
                new ApproovPinningHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        channelBuilder.hostnameVerifier(pinningHostnameVerifier);
    }

    public static ApproovChannelBuilder forAddress(String host, int port) {
        return new ApproovChannelBuilder(GrpcUtil.authorityFromHostAndPort(host, port));
    }

    public static ApproovChannelBuilder forTarget(String target) {
        return new ApproovChannelBuilder(target);
    }

    public static ApproovChannelBuilder forTarget(String target, ChannelCredentials creds) {
        return new ApproovChannelBuilder(target, creds);
    }

    @Override
    public ApproovChannelBuilder directExecutor() {
        channelBuilder.directExecutor();
        return this;
    }

    @Override
    public ApproovChannelBuilder executor(Executor executor) {
        channelBuilder.executor(executor);
        return this;
    }

    @Override
    public ApproovChannelBuilder intercept(ClientInterceptor... interceptors) {
        channelBuilder.intercept(interceptors);
        return this;
    }

    @Override
    public ApproovChannelBuilder intercept(List<io.grpc.ClientInterceptor> list) {
        channelBuilder.intercept(list);
        return this;
    }

    @Override
    public ApproovChannelBuilder userAgent(String userAgent) {
        channelBuilder.userAgent(userAgent);
        return this;
    }

    @Override
    public ApproovChannelBuilder overrideAuthority(String authority) {
        channelBuilder.overrideAuthority(authority);
        return this;
    }

    @Override
    @Deprecated
    public ApproovChannelBuilder nameResolverFactory(NameResolver.Factory resolverFactory) {
        channelBuilder.nameResolverFactory(resolverFactory);
        return this;
    }

    @Override
    public ApproovChannelBuilder decompressorRegistry(DecompressorRegistry registry) {
        channelBuilder.decompressorRegistry(registry);
        return this;
    }

    @Override
    public ApproovChannelBuilder compressorRegistry(CompressorRegistry registry) {
        channelBuilder.compressorRegistry(registry);
        return this;
    }

    @Override
    public ApproovChannelBuilder idleTimeout(long value, TimeUnit unit) {
        channelBuilder.idleTimeout(value, unit);
        return this;
    }

    @Override
    public ManagedChannel build() {
        return channelBuilder.build();
    }

}
