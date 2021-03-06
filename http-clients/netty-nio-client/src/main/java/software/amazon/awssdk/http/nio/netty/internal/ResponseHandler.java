/*
 * Copyright 2010-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.nio.netty.internal;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKeys.REQUEST_CONTEXT_KEY;

import com.typesafe.netty.http.HttpStreamsClientHandler;
import com.typesafe.netty.http.StreamedHttpResponse;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.AttributeKey;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.utils.FunctionalUtils.UnsafeRunnable;

@Sharable
class ResponseHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final Logger log = LoggerFactory.getLogger(ResponseHandler.class);

    /**
     * {@link AttributeKey} to keep track of whether we should close the connection after this request
     * has completed.
     */
    private static final AttributeKey<Boolean> KEEP_ALIVE = AttributeKey.newInstance("KeepAlive");

    @Override
    protected void channelRead0(ChannelHandlerContext channelContext, HttpObject msg) throws Exception {
        RequestContext requestContext = channelContext.channel().attr(REQUEST_CONTEXT_KEY).get();


        if (msg instanceof StreamedHttpResponse) {
            StreamedHttpResponse response = (StreamedHttpResponse) msg;
            SdkHttpResponse sdkResponse = SdkHttpFullResponse.builder()
                                                             .headers(fromNettyHeaders(response.headers()))
                                                             .statusCode(response.status().code())
                                                             .statusText(response.status().reasonPhrase())
                                                             .build();
            channelContext.channel().attr(KEEP_ALIVE).set(HttpUtil.isKeepAlive(response));
            requestContext.handler().headersReceived(sdkResponse);
            requestContext.handler().onStream(new PublisherAdapter(response, channelContext, requestContext));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        RequestContext requestContext = ctx.channel().attr(REQUEST_CONTEXT_KEY).get();
        try {
            log.error("Exception processing request: {}", requestContext.sdkRequest(), cause);
            ctx.fireExceptionCaught(cause);
        } finally {
            runAndLogError("SdkHttpResponseHandler threw an exception",
                () -> requestContext.handler().exceptionOccurred(cause));
            runAndLogError("Could not release channel back to the pool",
                () -> requestContext.channelPool().release(ctx.channel()));
        }
    }

    /**
     * Runs a given {@link UnsafeRunnable} and logs an error without throwing.
     *
     * @param errorMsg Message to log with exception thrown.
     * @param runnable Action to perform.
     */
    private static void runAndLogError(String errorMsg, UnsafeRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.error(errorMsg, e);
        }
    }

    private static Map<String, List<String>> fromNettyHeaders(HttpHeaders headers) {
        return headers.entries().stream()
                      .collect(groupingBy(Map.Entry::getKey,
                                          mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private static class PublisherAdapter implements Publisher<ByteBuffer> {
        private final StreamedHttpResponse response;
        private final ChannelHandlerContext channelContext;
        private final RequestContext requestContext;

        private PublisherAdapter(StreamedHttpResponse response, ChannelHandlerContext channelContext,
                                 RequestContext requestContext) {
            this.response = response;
            this.channelContext = channelContext;
            this.requestContext = requestContext;
        }

        @Override
        public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
            response.subscribe(new Subscriber<HttpContent>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(HttpContent httpContent) {
                    subscriber.onNext(httpContent.content().nioBuffer());
                    httpContent.release();
                    channelContext.read();
                }

                @Override
                public void onError(Throwable t) {
                    subscriber.onError(t);
                    requestContext.handler().exceptionOccurred(t);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                    requestContext.handler().complete();
                    if (!channelContext.channel().attr(KEEP_ALIVE).get()) {
                        channelContext.channel().close();
                    }
                    channelContext.pipeline().remove(HttpStreamsClientHandler.class);
                    channelContext.pipeline().remove(ResponseHandler.class);
                    requestContext.channelPool().release(channelContext.channel());
                }
            });
        }
    }
}
