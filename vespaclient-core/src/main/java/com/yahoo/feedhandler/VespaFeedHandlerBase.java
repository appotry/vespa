// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.feedhandler;

import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.document.DocumentTypeManager;
import com.yahoo.feedapi.FeedContext;
import com.yahoo.feedapi.MessagePropertyProcessor;
import com.yahoo.feedapi.SharedSender;
import com.yahoo.search.query.ParameterParser;


import java.io.InputStream;

public abstract class VespaFeedHandlerBase {

    protected FeedContext context;
    private final long defaultTimeoutMillis;

    VespaFeedHandlerBase(FeedContext context) {
        this(context, context.getPropertyProcessor().getDefaultTimeoutMillis());
    }

    private VespaFeedHandlerBase(FeedContext context, long defaultTimeoutMillis) {
        this.context = context;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    SharedSender getSharedSender(String route) {
        return context.getSharedSender(route);
    }

    MessagePropertyProcessor getPropertyProcessor() {
        return context.getPropertyProcessor();
    }

    /**
     * @param request Request object to get the POST data stream from
     * @return An InputStream that either is a GZIP wrapper or simply the
     *         original data stream.
     * @throws IllegalArgumentException if GZIP stream creation failed
     */
    InputStream getRequestInputStream(HttpRequest request) {
         return request.getData();
    }

    protected DocumentTypeManager getDocumentTypeManager() {
        return context.getDocumentTypeManager();
    }

    protected long getTimeoutMillis(HttpRequest request) {
        return ParameterParser.asMilliSeconds(request.getProperty("timeout"), defaultTimeoutMillis);
    }
    
}
