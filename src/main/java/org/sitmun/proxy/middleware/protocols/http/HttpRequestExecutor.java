package org.sitmun.proxy.middleware.protocols.http;

import static org.sitmun.proxy.middleware.dto.ProxyProblemResponses.upstreamAuthorizationFailure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.sitmun.proxy.middleware.dto.ProblemDetail;
import org.sitmun.proxy.middleware.dto.ProblemTypes;
import org.sitmun.proxy.middleware.service.RequestExecutor;
import org.sitmun.proxy.middleware.service.RequestExecutorResponse;
import org.sitmun.proxy.middleware.service.RequestExecutorResponseImpl;
import org.sitmun.proxy.middleware.servicecheck.UpstreamExchange;
import org.sitmun.proxy.middleware.utils.UriTemplateExpander;
import org.springframework.util.StringUtils;
import org.springframework.web.util.*;

@Slf4j
public class HttpRequestExecutor implements RequestExecutor {

  private static final String DEFAULT_POST_CONTENT_TYPE = "text/xml";
  private static final Set<String> HOP_BY_HOP_HEADERS =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailers",
          "transfer-encoding",
          "upgrade",
          "authorization",
          "cookie",
          "set-cookie",
          "www-authenticate");

  private final Map<String, String> headers = new HashMap<>();
  private final Map<String, String> parameters = new HashMap<>();
  private static final Executor REPORT_EXECUTOR =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "service-check-report");
            thread.setDaemon(true);
            return thread;
          });

  private final HttpClient httpClient;
  private final String baseUrl;
  @Setter private String url;
  @Setter private String body;
  private Consumer<UpstreamExchange> exchangeListener;
  private Executor listenerExecutor = REPORT_EXECUTOR;
  private UpstreamExchange upstreamExchange;

  public HttpRequestExecutor(String baseUrl, HttpClient httpClient) {
    this.baseUrl = baseUrl;
    this.httpClient = httpClient;
  }

  public void setHeader(String header, String value) {
    headers.put(header, value);
  }

  /** Returns the header value set on this executor (for tests). */
  public String getHeader(String name) {
    return headers.get(name);
  }

  public void setParameters(Map<String, String> parameters) {
    if (parameters != null && !parameters.isEmpty()) {
      this.parameters.putAll(parameters);
    }
  }

  public void addParameter(String key, String value) {
    if (StringUtils.hasText(key) && value != null) {
      this.parameters.put(key, value);
    }
  }

  public void setExchangeListener(Consumer<UpstreamExchange> exchangeListener) {
    this.exchangeListener = exchangeListener;
  }

  void setListenerExecutor(Executor listenerExecutor) {
    this.listenerExecutor = listenerExecutor;
  }

  public UpstreamExchange upstreamExchange() {
    return upstreamExchange;
  }

  @SuppressWarnings("unchecked")
  @Override
  public RequestExecutorResponse<?> execute() {
    if (!StringUtils.hasText(url)) {
      throw new IllegalStateException("Url is not set");
    }

    Request httpRequest;
    try {
      httpRequest = buildRequest();
    } catch (QueryConflictException e) {
      ProblemDetail problem =
          ProblemDetail.builder()
              .type(ProblemTypes.PROXY_CONFIG_ERROR)
              .status(502)
              .title("Proxy Configuration Error")
              .detail("Configured query conflicts with request parameters")
              .instance("/proxy")
              .properties(Map.of("origin", "backend-config"))
              .build();
      return new RequestExecutorResponseImpl<>(baseUrl, 502, "application/problem+json", problem);
    }

    log.debug(
        "Executing upstream request: method={} headerNames={}",
        httpRequest.method(),
        httpRequest.headers().names());

    long started = System.nanoTime();
    try (Response r = httpClient.executeRequest(httpRequest)) {
      String contentType = r.header("content-type");
      if (r.code() == 401 || r.code() == 403) {
        publish(httpRequest, r.code(), contentType, null, null, started);
        return new RequestExecutorResponseImpl<>(
            baseUrl, 502, "application/problem+json", upstreamAuthorizationFailure());
      }
      ResponseBody responseBody = r.body();
      byte[] bytes = responseBody == null ? null : responseBody.bytes();
      publish(httpRequest, r.code(), contentType, bytes, null, started);
      return new RequestExecutorResponseImpl<>(baseUrl, r.code(), contentType, bytes);
    } catch (IOException e) {
      publish(httpRequest, null, null, null, e, started);
      log.error("Upstream request failed with exception type {}", e.getClass().getSimpleName());
      ProblemDetail problem =
          ProblemDetail.builder()
              .type(ProblemTypes.PROXY_SERVICE_ERROR)
              .status(503)
              .title("Service Error")
              .detail("Error with the request to final service")
              .instance("")
              .build();
      return new RequestExecutorResponseImpl<>(baseUrl, 503, "application/problem+json", problem);
    }
  }

  private void publish(
      Request httpRequest,
      Integer httpStatus,
      String contentType,
      byte[] bytes,
      Throwable transportError,
      long started) {
    UpstreamExchange exchange =
        new UpstreamExchange(
            httpRequest.method(),
            URI.create(httpRequest.url().toString()),
            httpStatus,
            contentType,
            bytes,
            transportError,
            Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
            Instant.now());
    upstreamExchange = exchange;
    if (exchangeListener == null) {
      return;
    }
    listenerExecutor.execute(
        () -> {
          try {
            exchangeListener.accept(exchange);
          } catch (RuntimeException ex) {
            log.warn("Service check listener failed: {}", ex.getClass().getSimpleName());
          }
        });
  }

  /**
   * Executes the request and returns a streamable body without buffering via {@code body.bytes()}.
   * Caller must close the returned response.
   */
  public StreamedHttpResponse executeStreaming() throws IOException {
    if (!StringUtils.hasText(url)) {
      throw new IllegalStateException("Url is not set");
    }

    Request httpRequest;
    try {
      httpRequest = buildRequest();
    } catch (QueryConflictException e) {
      throw new IOException("Configured query conflicts with request parameters");
    }
    log.debug(
        "Executing streaming upstream request: method={} headerNames={}",
        httpRequest.method(),
        httpRequest.headers().names());

    Response response = httpClient.executeRequest(httpRequest);
    ResponseBody responseBody = response.body();
    InputStream stream =
        responseBody != null ? responseBody.byteStream() : InputStream.nullInputStream();
    return new StreamedHttpResponse(response, stream);
  }

  private Request buildRequest() {
    Request.Builder builder = new Request.Builder();
    builder.url(getUrl());
    for (String k : headers.keySet()) {
      builder.addHeader(k, headers.get(k));
    }

    if (body != null) {
      String contentType = resolvePostContentType();
      RequestBody requestBody = RequestBody.create(body, MediaType.parse(contentType));
      builder.post(requestBody);
    }
    return builder.build();
  }

  private String resolvePostContentType() {
    String fromHeader = headers.get("Content-Type");
    if (!StringUtils.hasText(fromHeader)) {
      fromHeader = headers.get("content-type");
    }
    return StringUtils.hasText(fromHeader) ? fromHeader : DEFAULT_POST_CONTENT_TYPE;
  }

  public String getUrl() {
    if (parameters.isEmpty()) {
      return url;
    }

    String expandedUrl = url;
    Set<String> usedVariables = Set.of();
    if (UriTemplateExpander.hasTemplateVariables(url)) {
      UriTemplateExpander.ExpandedResult result =
          UriTemplateExpander.expandWithUsedVariables(url, parameters);
      expandedUrl = result.getUri();
      usedVariables = result.getUsedVariables();
    }
    return mergeQuery(expandedUrl, usedVariables);
  }

  private String mergeQuery(String expandedUrl, Set<String> usedVariables) {
    int fragmentStart = expandedUrl.indexOf('#');
    String fragment = fragmentStart >= 0 ? expandedUrl.substring(fragmentStart) : "";
    String beforeFragment = fragmentStart >= 0 ? expandedUrl.substring(0, fragmentStart) : expandedUrl;
    int queryStart = beforeFragment.indexOf('?');
    String rawQuery = queryStart >= 0 ? beforeFragment.substring(queryStart + 1) : "";
    StringBuilder additions = new StringBuilder();

    for (Map.Entry<String, String> parameter : parameters.entrySet()) {
      String key = parameter.getKey();
      String value = parameter.getValue();
      boolean found = false;
      if (!rawQuery.isEmpty()) {
        for (String component : rawQuery.split("&", -1)) {
          int equals = component.indexOf('=');
          String rawName = equals >= 0 ? component.substring(0, equals) : component;
          if (!URLDecoder.decode(rawName, StandardCharsets.UTF_8).equals(key)) {
            continue;
          }
          found = true;
          if (equals < 0 || value == null
              || !URLDecoder.decode(component.substring(equals + 1), StandardCharsets.UTF_8)
                  .equals(value)) {
            throw new QueryConflictException();
          }
        }
      }
      if (!found && !usedVariables.contains(key)) {
        if (value == null) {
          throw new QueryConflictException();
        }
        if (!rawQuery.isEmpty() || additions.length() > 0) {
          additions.append('&');
        }
        additions.append(UriUtils.encodeQueryParam(key, StandardCharsets.UTF_8).replace("+", "%2B"))
            .append('=')
            .append(UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8).replace("+", "%2B"));
      }
    }
    if (additions.length() == 0) {
      return expandedUrl;
    }
    return beforeFragment + (queryStart < 0 ? "?" : "") + additions + fragment;
  }

  private static final class QueryConflictException extends RuntimeException {}

  public String describe() {
    return "HttpRequest{"
        + "method="
        + (body == null ? "GET" : "POST")
        + ", headerNames="
        + headers.keySet()
        + ", parameterNames="
        + parameters.keySet()
        + '}';
  }

  /** Whether an upstream response header should be forwarded to the client. */
  public static boolean isForwardableResponseHeader(String name) {
    return name != null && !HOP_BY_HOP_HEADERS.contains(name.toLowerCase());
  }

  /** Open OkHttp response with a body stream; must be closed by the caller. */
  public record StreamedHttpResponse(Response response, InputStream bodyStream)
      implements AutoCloseable {
    @Override
    public void close() {
      response.close();
    }
  }
}
