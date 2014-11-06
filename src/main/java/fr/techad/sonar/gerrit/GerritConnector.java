package fr.techad.sonar.gerrit;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.auth.DigestScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GerritConnector {
    private static final Logger LOG = LoggerFactory.getLogger(GerritConnector.class);
    private static final String BASIC_AUTH_SCHEME = "BASIC";
    private static final String DIGEST_AUTH_SCHEME = "DIGEST";
    private static final String URI_AUTH_PREFIX = "/a";
    private static final String URI_CHANGES = "/changes/%s~%s~%s";
    private static final String URI_REVISIONS = "/revisions/%s";
    private static final String URI_LIST_FILES_SUFFIX = "/files/";
    private static final String URI_SET_REVIEW = "/review";
    private static int REQUEST_COUNTER;
    private String scheme;
    private String host;
    private int port;
    private String username;
    private String password;
    private String basePath;
    private String httpAuthScheme;
    private HttpHost httpHost;
    private CloseableHttpClient httpClient;
    private HttpClientContext httpClientContext;

    public GerritConnector(String host, int port, String username, String password) {
        this("http", host, port, username, password, "/", DIGEST_AUTH_SCHEME);
    }

    public GerritConnector(String scheme, String host, int port, String username, String password, String basePath,
            String as) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.basePath = basePath;
        this.httpAuthScheme = as;
    }

    @NotNull
    public String listFiles(String projectName, String branchName, String changeId, String revisionId)
            throws IOException {
        String getUri = rootUriBuilder(projectName, branchName, changeId, revisionId);
        getUri = getUri.concat(URI_LIST_FILES_SUFFIX);

        LOG.info("[GERRIT PLUGIN] Listing files from {}", getUri);

        HttpGet httpGet = new HttpGet(getUri);
        CloseableHttpResponse httpResponse = logAndExecute(httpGet);
        return consumeAndLogEntity(httpResponse);
    }

    @NotNull
    public String setReview(String projectName, String branchName, String changeId, String revisionId,
            String reviewInputAsJson) throws IOException {
        LOG.info("[GERRIT PLUGIN] Setting review {}", reviewInputAsJson);

        String postUri = rootUriBuilder(projectName, branchName, changeId, revisionId);
        postUri = postUri.concat(URI_SET_REVIEW);

        LOG.info("[GERRIT PLUGIN] Setting review at {}", postUri);

        HttpPost httpPost = new HttpPost(postUri);
        httpPost.setEntity(new StringEntity(reviewInputAsJson, ContentType.APPLICATION_JSON));

        CloseableHttpResponse httpResponse = logAndExecute(httpPost);
        return consumeAndLogEntity(httpResponse);
    }

    // Example
    // http://hc.apache.org/httpcomponents-client-ga/httpclient/examples/org/apache/http/examples/client/ClientPreemptiveDigestAuthentication.java
    private void createHttpContext() {
        httpHost = new HttpHost(host, port, scheme);
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(new AuthScope(host, port), new UsernamePasswordCredentials(username,
                password));
        httpClient = HttpClients.custom().setDefaultCredentialsProvider(credentialsProvider).build();

        BasicAuthCache basicAuthCache = new BasicAuthCache();
        AuthScheme authScheme = null;

        if (BASIC_AUTH_SCHEME.equalsIgnoreCase(httpAuthScheme)) {
            authScheme = new BasicScheme();
        } else if (DIGEST_AUTH_SCHEME.equalsIgnoreCase(httpAuthScheme)) {
            authScheme = new DigestScheme();
        } else {
            LOG.error("[GERRIT PLUGIN] createHttpContext called with AUTH_SCHEME {} instead of digest or basic",
                    httpAuthScheme);
        }

        basicAuthCache.put(httpHost, authScheme);
        httpClientContext = HttpClientContext.create();
        httpClientContext.setAuthCache(basicAuthCache);
    }

    @NotNull
    private CloseableHttpResponse logAndExecute(@NotNull HttpRequestBase request) throws IOException {
        if (null == httpClient || null == httpClientContext || null == httpHost) {
            createHttpContext();
        }

        LOG.info("[GERRIT PLUGIN] Request {}: {} to {}", new Object[] { ++REQUEST_COUNTER, request.getMethod(),
                request.getURI().toString() });
        CloseableHttpResponse httpResponse = httpClient.execute(httpHost, request, httpClientContext);
        LOG.info("[GERRIT PLUGIN] Response {}: {}", REQUEST_COUNTER, httpResponse.getStatusLine().toString());
        return httpResponse;
    }

    @NotNull
    private String consumeAndLogEntity(@NotNull CloseableHttpResponse response) throws IOException {
        if (response.getEntity() == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("[GERRIT PLUGIN] Entity {}: no entity", REQUEST_COUNTER);
            }
            return StringUtils.EMPTY;
        }
        String content = EntityUtils.toString(response.getEntity());
        LOG.info("[GERRIT PLUGIN] Entity {}: {}", REQUEST_COUNTER, content);
        return content;
    }

    @NotNull
    private String encode(String content) {
        String result = "";
        try {
            result = URLEncoder.encode(content, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            LOG.error("[GERRIT PLUGIN] Error during encodage", e);
        }
        return result;
    }

    @NotNull
    public String rootUriBuilder(String projectName, String branchName, String changeId, String revisionId) {
        if ("/".compareTo(basePath) == 0) {
            basePath = "";
        }

        String uri = basePath;
        uri = uri.concat(URI_AUTH_PREFIX);
        uri = uri.concat(String.format(URI_CHANGES, encode(projectName), encode(branchName), encode(changeId)));
        uri = uri.concat(String.format(URI_REVISIONS, encode(revisionId)));

        if (LOG.isDebugEnabled()) {
            LOG.debug("[GERRIT PLUGIN] Built URI : {}", uri);
        }

        return uri;
    }
}
