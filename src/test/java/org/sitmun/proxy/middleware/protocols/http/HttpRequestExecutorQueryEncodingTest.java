package org.sitmun.proxy.middleware.protocols.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sitmun.proxy.middleware.dto.ProblemDetail;

/** Query values must survive exactly one form-style decoding by the upstream servlet. */
@ExtendWith(MockitoExtension.class)
class HttpRequestExecutorQueryEncodingTest {

  private static final String OUTPUT_FORMAT = "application/gml+xml; version=3.2";
  private static final String SCHEMA =
      "<x:schema xmlns:x=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"http://menorca\"/>";
  @Mock private HttpClient httpClient;

  @ParameterizedTest
  @MethodSource("queryComponents")
  void roundTripsNewParameterNamesAndValuesAfterOneDecoding(String name, String value) {
    var executor = executor("http://configured.test/wfs");
    executor.addParameter(name, value);

    String url = executor.getUrl();
    String[] component = URI.create(url).getRawQuery().split("=", 2);
    assertThat(URLDecoder.decode(component[0], StandardCharsets.UTF_8)).isEqualTo(name);
    assertThat(URLDecoder.decode(component[1], StandardCharsets.UTF_8)).isEqualTo(value);
    assertThat(new Request.Builder().url(url).build().url().queryParameter(name)).isEqualTo(value);
  }

  private static Stream<Arguments> queryComponents() {
    return Stream.of(
        Arguments.of("outputFormat", OUTPUT_FORMAT),
        Arguments.of("name+with space", "A+B C"),
        Arguments.of("literalPercent", "%2B %20 + 100%"),
        Arguments.of("filter", "name='A+B' AND kind='C & D'"),
        Arguments.of("reserved", "&=;?#/:[]"),
        Arguments.of("unicode", "Maó + comerç"),
        Arguments.of("empty", ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://configured.test/wfs",
        "http://configured.test/wfs?routing=keep%2Bplus+space%20and%252B",
        "http://configured.test/{workspace}/wfs?existing=value"
      })
  void sendsDescribeFeatureTypeWithTheExactOutputFormat(String upstreamUrl) throws IOException {
    var executor = describeFeatureType(upstreamUrl);
    stubSchemaResponse();

    var response = executor.execute().asResponseEntity();
    Request request = capturedRequest();

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo(SCHEMA.getBytes(StandardCharsets.UTF_8));
    assertThat(request.method()).isEqualTo("GET");
    assertThat(request.url().queryParameter("service")).isEqualTo("WFS");
    assertThat(request.url().queryParameter("version")).isEqualTo("2.0.0");
    assertThat(request.url().queryParameter("request")).isEqualTo("DescribeFeatureType");
    assertThat(request.url().queryParameter("typename")).isEqualTo("menorca:shops");
    assertThat(request.url().queryParameter("outputFormat")).isEqualTo(OUTPUT_FORMAT);
    assertThat(request.url().encodedQuery()).contains("gml%2Bxml");
    if (upstreamUrl.contains("routing=")) {
      assertThat(request.url().queryParameter("routing")).isEqualTo("keep+plus space and%2B");
    }
    if (upstreamUrl.contains("{workspace}")) {
      assertThat(request.url().encodedPath()).isEqualTo("/menorca/wfs");
      assertThat(request.url().queryParameterNames()).doesNotContain("workspace");
    }
  }

  @Test
  void preservesTheRawBasePathQueryAndFragmentWhenAppendingParameters() {
    String rawQuery = "routing=A%2BB+space%20and%252B&empty=&flag";
    var executor =
        executor("http://configured.test/workspace%2Fpart/wfs?" + rawQuery + "#section%2B1");
    executor.addParameter("outputFormat", OUTPUT_FORMAT);

    URI result = URI.create(executor.getUrl());

    assertThat(result.getRawPath()).isEqualTo("/workspace%2Fpart/wfs");
    assertThat(result.getRawQuery()).startsWith(rawQuery + "&outputFormat=");
    assertThat(result.getRawFragment()).isEqualTo("section%2B1");
    var request = new Request.Builder().url(executor.getUrl()).build();
    assertThat(request.url().queryParameter("routing")).isEqualTo("A+B space and%2B");
    assertThat(request.url().queryParameter("outputFormat")).isEqualTo(OUTPUT_FORMAT);
  }

  @Test
  void doesNotDuplicateAnAlreadyEncodedBaseQueryValue() {
    String rawQuery = "outputFormat=application%2Fgml%2Bxml%3B%20version%3D3.2";
    var executor = executor("http://configured.test/wfs?" + rawQuery);
    executor.addParameter("outputFormat", OUTPUT_FORMAT);
    executor.addParameter("service", "WFS");

    String result = executor.getUrl();
    var request = new Request.Builder().url(result).build();

    assertThat(URI.create(result).getRawQuery()).isEqualTo(rawQuery + "&service=WFS");
    assertThat(request.url().queryParameterValues("outputFormat")).containsExactly(OUTPUT_FORMAT);
  }

  @Test
  void returnsTheOriginalEncodedUrlWhenThereAreNoNewParameters() {
    String url = "http://configured.test/workspace%2Fpart/wfs?q=A%2BB+space#section%2B1";

    assertThat(executor(url).getUrl()).isEqualTo(url);
  }

  @Test
  void preservesTheOutputFormatWhenStreamingTheResponse() throws IOException {
    var executor = describeFeatureType("http://configured.test/wfs");
    stubSchemaResponse();

    try (var response = executor.executeStreaming()) {
      assertThat(response.bodyStream().readAllBytes())
          .isEqualTo(SCHEMA.getBytes(StandardCharsets.UTF_8));
    }

    assertThat(capturedRequest().url().queryParameter("outputFormat")).isEqualTo(OUTPUT_FORMAT);
  }

  @Test
  void preservesPostXmlAndHeadersWhileEncodingQueryValues() throws IOException {
    var executor = describeFeatureType("http://configured.test/wfs");
    String xml = "<w:Transaction xmlns:w=\"http://www.opengis.net/wfs/2.0\"/>";
    executor.addParameter("request", "Transaction");
    executor.setBody(xml);
    executor.setHeader("Content-Type", "application/xml");
    executor.setHeader("Authorization", "Bearer test-only-upstream-token");
    stubSchemaResponse();

    executor.execute();
    Request request = capturedRequest();

    assertThat(request.method()).isEqualTo("POST");
    assertThat(request.header("Authorization")).isEqualTo("Bearer test-only-upstream-token");
    assertThat(request.body().contentType().toString()).contains("application/xml");
    var body = new Buffer();
    request.body().writeTo(body);
    assertThat(body.readUtf8()).isEqualTo(xml);
    assertThat(request.url().queryParameter("request")).isEqualTo("Transaction");
    assertThat(request.url().queryParameter("outputFormat")).isEqualTo(OUTPUT_FORMAT);
  }

  @ParameterizedTest
  @MethodSource("conflictingQueries")
  void rejectsEveryConflictingValueWithoutContactingUpstream(
      String url, String name, String value) {
    var executor = executor(url);
    executor.addParameter(name, value);
    var response = executor.execute().asResponseEntity();

    assertThat(response.getStatusCode().value()).isEqualTo(502);
    assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
    var problem = (ProblemDetail) response.getBody();
    assertThat(problem.getType()).isEqualTo("https://sitmun.org/problems/proxy-config-error");
    assertThat(problem.toString()).doesNotContain("configured.test", "secret");
    verifyNoInteractions(httpClient);
  }

  private static Stream<Arguments> conflictingQueries() {
    return Stream.of(
        Arguments.of("http://configured.test/wfs?format=application/gml+xml", "format", "application/gml+xml"),
        Arguments.of("http://configured.test/wfs?flag", "flag", "value"),
        Arguments.of("http://configured.test/wfs?flag", "flag", ""),
        Arguments.of("http://configured.test/wfs?%66lag=&flag", "flag", ""),
        Arguments.of("http://configured.test/wfs?x=same&x=different", "x", "same"),
        Arguments.of("http://configured.test/wfs?x=different&x=same", "x", "same"),
        Arguments.of("http://configured.test/wfs?%73ecret=one", "secret", "two"),
        Arguments.of("http://configured.test/{x}/wfs?x=other", "x", "same"),
        Arguments.of("http://configured.test/wfs?x={x}&x=other", "x", "same"));
  }

  @ParameterizedTest
  @MethodSource("equivalentQueries")
  void preservesBaseWhenAllExistingValuesAreEquivalent(String url, String name, String value)
      throws IOException {
    var executor = executor(url);
    executor.addParameter(name, value);
    stubSchemaResponse();
    executor.execute();
    assertThat(executor.getUrl()).isEqualTo(url);
    assertThat(capturedRequest().url().queryParameterValues(name)).allMatch(value::equals);
  }

  private static Stream<Arguments> equivalentQueries() {
    return Stream.of(
        Arguments.of("http://configured.test/wfs?format=application%2Fgml%2Bxml", "format", "application/gml+xml"),
        Arguments.of("http://configured.test/wfs?%66lag=", "flag", ""),
        Arguments.of("http://configured.test/wfs?x=A+B&%78=A%20B", "x", "A B"),
        Arguments.of("http://configured.test/wfs?x=%252B&x=%252B", "x", "%2B"),
        Arguments.of("http://configured.test/wfs?%C3%A0=Ma%C3%B3", "à", "Maó"));
  }

  @Test
  void preservesAbsentValueAndFragmentWhenAddingAnotherNameToTemplate() throws IOException {
    var executor = executor("http://configured.test/{workspace}/wfs?flag#part%2B1");
    executor.addParameter("workspace", "menorca");
    executor.addParameter("other", "");
    stubSchemaResponse();
    executor.execute();
    assertThat(executor.getUrl())
        .isEqualTo("http://configured.test/menorca/wfs?flag&other=#part%2B1");
    assertThat(capturedRequest().url().encodedQuery()).isEqualTo("flag&other=");
  }

  @Test
  void preservesEncodedTemplateLiteralsAndDeduplicatesConsumedQueryVariable() throws IOException {
    var executor =
        executor("http://configured.test/a%2Fb/{workspace}?workspace=menorca&format={format}&routing=A%2BB+space#part%2B1");
    executor.addParameter("workspace", "menorca");
    executor.addParameter("format", "application/gml+xml");
    executor.addParameter("extra", "Maó");
    stubSchemaResponse();
    executor.execute();
    assertThat(executor.getUrl())
        .isEqualTo("http://configured.test/a%2Fb/menorca?workspace=menorca&format=application%2Fgml%2Bxml&routing=A%2BB+space&extra=Ma%C3%B3#part%2B1");
    assertThat(capturedRequest().url().queryParameterValues("format"))
        .containsExactly("application/gml+xml");
  }

  @Test
  void rejectsStreamingConflictWithControlledIOException() {
    var executor = executor("http://configured.test/wfs?flag");
    executor.addParameter("flag", "");
    assertThatThrownBy(executor::executeStreaming)
        .isInstanceOf(IOException.class)
        .hasMessage("Configured query conflicts with request parameters");
    verifyNoInteractions(httpClient);
  }

  @Test
  void rejectsPostConflictBeforeSendingBodyOrHeaders() {
    var executor = executor("http://configured.test/wfs?flag=old");
    executor.addParameter("flag", "new");
    executor.setBody("<xml/>");
    executor.setHeader("Authorization", "Bearer secret");
    assertThat(executor.execute().asResponseEntity().getStatusCode().value()).isEqualTo(502);
    verifyNoInteractions(httpClient);
  }

  private HttpRequestExecutor executor(String upstreamUrl) {
    var executor = new HttpRequestExecutor("http://proxy.test", httpClient);
    executor.setUrl(upstreamUrl);
    return executor;
  }

  private HttpRequestExecutor describeFeatureType(String upstreamUrl) {
    var executor = executor(upstreamUrl);
    executor.addParameter("service", "WFS");
    executor.addParameter("version", "2.0.0");
    executor.addParameter("request", "DescribeFeatureType");
    executor.addParameter("typename", "menorca:shops");
    executor.addParameter("outputFormat", OUTPUT_FORMAT);
    if (upstreamUrl.contains("{workspace}")) {
      executor.addParameter("workspace", "menorca");
    }
    return executor;
  }

  private void stubSchemaResponse() throws IOException {
    when(httpClient.executeRequest(any(Request.class)))
        .thenAnswer(
            call ->
                new Response.Builder()
                    .request(call.getArgument(0))
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/xml")
                    .body(ResponseBody.create(SCHEMA, MediaType.parse("application/xml")))
                    .build());
  }

  private Request capturedRequest() throws IOException {
    var request = ArgumentCaptor.forClass(Request.class);
    verify(httpClient).executeRequest(request.capture());
    return request.getValue();
  }
}
