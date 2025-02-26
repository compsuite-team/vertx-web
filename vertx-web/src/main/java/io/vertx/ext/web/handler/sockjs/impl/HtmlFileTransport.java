/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.web.handler.sockjs.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.PlatformHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.regex.Pattern;

import static io.vertx.core.buffer.Buffer.buffer;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 * @author <a href="mailto:plopes@redhat.com">Paulo Lopes</a>
 */
class HtmlFileTransport extends BaseTransport {

  private static final Logger LOG = LoggerFactory.getLogger(HtmlFileTransport.class);

  private static final Pattern CALLBACK_VALIDATION = Pattern.compile("[^a-zA-Z0-9-_.]");

  private static final String HTML_FILE_TEMPLATE;

  static {
    String str =
      "<!doctype html>\n" +
        "<html><head>\n" +
        "  <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\" />\n" +
        "  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n" +
        "</head><body><h2>Don't panic!</h2>\n" +
        "  <script>\n" +
        "    document.domain = document.domain;\n" +
        "    var c = parent.{{ callback }};\n" +
        "    c.start();\n" +
        "    function p(d) {c.message(d);};\n" +
        "    window.onload = function() {c.stop();};\n" +
        "  </script>";

    String str2 = str.replace("{{ callback }}", "");
    StringBuilder sb = new StringBuilder(str);
    int extra = 1024 - str2.length();
    for (int i = 0; i < extra; i++) {
      sb.append(' ');
    }
    sb.append("\r\n");
    HTML_FILE_TEMPLATE = sb.toString();
  }

  private final Handler<SockJSSocket> sockHandler;

  HtmlFileTransport(Vertx vertx, Router router, LocalMap<String, SockJSSession> sessions, SockJSHandlerOptions options, Handler<SockJSSocket> sockHandler) {
    super(vertx, sessions, options);

    this.sockHandler = sockHandler;

    String htmlFileRE = COMMON_PATH_ELEMENT_RE + "htmlfile.*";

    router.getWithRegex(htmlFileRE)
      .handler((PlatformHandler) this::handleGet);
  }

  private void handleGet(RoutingContext ctx) {
    String callback = ctx.request().getParam("callback");
    if (callback == null) {
      callback = ctx.request().getParam("c");
      if (callback == null) {
        ctx.response().setStatusCode(500).end("\"callback\" parameter required\n");
        return;
      }
    }

    if (CALLBACK_VALIDATION.matcher(callback).find()) {
      ctx.response().setStatusCode(500);
      ctx.response().end("invalid \"callback\" parameter\n");
      return;
    }

    HttpServerRequest req = ctx.request();
    String sessionID = req.params().get("param0");
    SockJSSession session = getSession(ctx, options, sessionID, sockHandler);
    session.register(req, new HtmlFileListener(options.getMaxBytesStreaming(), ctx, callback, session));
  }

  private class HtmlFileListener extends BaseListener {

    final int maxBytesStreaming;
    final String callback;
    boolean headersWritten;
    int bytesSent;

    HtmlFileListener(int maxBytesStreaming, RoutingContext rc, String callback, SockJSSession session) {
      super(rc, session);
      this.maxBytesStreaming = maxBytesStreaming;
      this.callback = callback;
      addCloseHandler(rc.response(), session);
    }

    @Override
    public Future<Void> sendFrame(String body) {
      if (LOG.isTraceEnabled()) LOG.trace("HtmlFile, sending frame");
      if (!headersWritten) {
        String htmlFile = HTML_FILE_TEMPLATE.replace("{{ callback }}", callback);
        rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8");
        setNoCacheHeaders(rc);
        rc.response().setChunked(true);
        setJSESSIONID(options, rc);
        rc.response().write(htmlFile);
        headersWritten = true;
      }
      body = escapeForJavaScript(body);
      String sb = "<script>\np(\"" +
        body +
        "\");\n</script>\r\n";
      Buffer buff = buffer(sb);
      Future<Void> fut = rc.response().write(buff);
      bytesSent += buff.length();
      if (bytesSent >= maxBytesStreaming) {
        if (LOG.isTraceEnabled()) LOG.trace("More than maxBytes sent so closing connection");
        // Reset and close the connection
        close();
      }
      return fut;
    }

    @Override
    public void close() {
      if (!closed) {
        try {
          session.resetListener();
          rc.response().end();
          rc.response().close();
          closed = true;
        } catch (IllegalStateException e) {
          // Underlying connection might already be closed - that's fine
        }
      }
    }
  }
}
