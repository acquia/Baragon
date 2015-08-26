package com.hubspot.baragon.client;

import javax.inject.Provider;

import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.List;
import java.util.Collection;

import static com.google.common.base.Preconditions.checkNotNull;

import com.hubspot.baragon.models.BaragonGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hubspot.baragon.models.BaragonAgentMetadata;
import com.hubspot.baragon.models.BaragonRequest;
import com.hubspot.baragon.models.BaragonResponse;
import com.hubspot.baragon.models.BaragonService;
import com.hubspot.baragon.models.BaragonServiceState;
import com.hubspot.baragon.models.BaragonServiceStatus;
import com.hubspot.baragon.models.QueuedRequestId;
import com.hubspot.horizon.HttpClient;
import com.hubspot.horizon.HttpRequest;
import com.hubspot.horizon.HttpRequest.Method;
import com.hubspot.horizon.HttpResponse;
import com.fasterxml.jackson.core.type.TypeReference;

public class BaragonServiceClient {

  private static final Logger LOG = LoggerFactory.getLogger(BaragonServiceClient.class);

  private static final String WORKERS_FORMAT = "http://%s/%s/workers";

  private static final String LOAD_BALANCER_FORMAT = "http://%s/%s/load-balancer";
  private static final String LOAD_BALANCER_BASE_PATH_FORMAT = LOAD_BALANCER_FORMAT + "/%s/base-path";
  private static final String LOAD_BALANCER_ALL_BASE_PATHS_FORMAT = LOAD_BALANCER_BASE_PATH_FORMAT + "/all";
  private static final String LOAD_BALANCER_AGENTS_FORMAT = LOAD_BALANCER_FORMAT + "/%s/agents";
  private static final String LOAD_BALANCER_KNOWN_AGENTS_FORMAT = LOAD_BALANCER_FORMAT + "/%s/known-agents";
  private static final String LOAD_BALANCER_DELETE_KNOWN_AGENT_FORMAT = LOAD_BALANCER_KNOWN_AGENTS_FORMAT + "/%s";
  private static final String LOAD_BALANCER_GROUP_FORMAT = LOAD_BALANCER_FORMAT + "/%s";
  private static final String LOAD_BALANCER_TRAFFIC_SOURCE_FORMAT = LOAD_BALANCER_GROUP_FORMAT + "/sources";

  private static final String REQUEST_FORMAT = "http://%s/%s/request";
  private static final String REQUEST_ID_FORMAT = REQUEST_FORMAT + "/%s";

  private static final String STATE_FORMAT = "http://%s/%s/state";
  private static final String STATE_SERVICE_ID_FORMAT = STATE_FORMAT + "/%s";
  private static final String STATE_RELOAD_FORMAT = STATE_SERVICE_ID_FORMAT + "/reload";

  private static final String STATUS_FORMAT = "http://%s/%s/status";

  private static final TypeReference<Collection<String>> STRING_COLLECTION = new TypeReference<Collection<String>>() {};
  private static final TypeReference<Collection<BaragonAgentMetadata>> BARAGON_AGENTS_COLLECTION = new TypeReference<Collection<BaragonAgentMetadata>>() {};
  private static final TypeReference<Collection<QueuedRequestId>> QUEUED_REQUEST_COLLECTION = new TypeReference<Collection<QueuedRequestId>>() {};
  private static final TypeReference<Collection<BaragonServiceState>> BARAGON_SERVICE_STATE_COLLECTION = new TypeReference<Collection<BaragonServiceState>>() {};

  private final Random random;
  private final Provider<List<String>> hostsProvider;
  private final String contextPath;
  private final Provider<Optional<String>> authkeyProvider;

  private final HttpClient httpClient;

  public BaragonServiceClient(String contextPath, HttpClient httpClient, List<String> hosts, Optional<String> authkey) {
    this(contextPath, httpClient, ProviderUtils.<List<String>>of(ImmutableList.copyOf(hosts)), ProviderUtils.of(authkey));
  }

  public BaragonServiceClient(String contextPath, HttpClient httpClient, Provider<List<String>> hostsProvider, Provider<Optional<String>> authkeyProvider) {
    this.httpClient = httpClient;
    this.contextPath = contextPath;
    this.hostsProvider = hostsProvider;
    this.authkeyProvider = authkeyProvider;
    this.random = new Random();
  }

  private String getHost() {
    final List<String> hosts = hostsProvider.get();
    return hosts.get(random.nextInt(hosts.size()));
  }

  private HttpRequest.Builder buildRequest(String uri) {
    return buildRequest(uri, null);
  }

  private HttpRequest.Builder buildRequest(String uri, Map<String, String> queryParams) {
    final HttpRequest.Builder builder = HttpRequest.newBuilder().setUrl(uri);

    final Optional<String> maybeAuthkey = authkeyProvider.get();

    if (maybeAuthkey.isPresent()) {
      builder.setQueryParam("authkey").to(maybeAuthkey.get());
    }

    if ((queryParams != null) && (!queryParams.isEmpty())) {
      for (Map.Entry<String, String> entry : queryParams.entrySet()) {
        builder.setQueryParam(entry.getKey()).to(entry.getValue());
      }
    }

    return builder;
  }

  private void checkResponse(String type, HttpResponse response) {
    if (response.isError()) {
      throw fail(type, response);
    }
  }

  private BaragonClientException fail(String type, HttpResponse response) {
    String body = "";
    try {
      body = response.getAsString();
    } catch (Exception e) {
      LOG.warn("Unable to read body", e);
    }

    String uri = "";
    try {
      uri = response.getRequest().getUrl().toString();
    } catch (Exception e) {
      LOG.warn("Unable to read uri", e);
    }

    throw new BaragonClientException(String.format("Failed '%s' action on Baragon (%s) - code: %s, %s", type, uri, response.getStatusCode(), body), response.getStatusCode());
  }

  private <T> Optional<T> getSingle(String uri, String type, String id, Class<T> clazz) {
    return getSingle(uri, type, id, clazz, null);
  }

  private <T> Optional<T> getSingle(String uri, String type, String id, Class<T> clazz, Map<String, String> queryParams) {
    checkNotNull(id, String.format("Provide a %s id", type));
    LOG.debug("Getting {} {} from {}", type, id, uri);
    final long start = System.currentTimeMillis();

    HttpResponse response = httpClient.execute(buildRequest(uri, queryParams).build());

    if (response.getStatusCode() == 404) {
      return Optional.absent();
    }

    checkResponse(type, response);
    LOG.info("Got {} {} in {}ms", type, id, System.currentTimeMillis() - start);
    return Optional.fromNullable(response.getAs(clazz));
  }

  private <T> Collection<T> getCollection(String uri, String type, TypeReference<Collection<T>> typeReference) {
    LOG.debug("Getting all {} from {}", type, uri);
    final long start = System.currentTimeMillis();

    HttpResponse response = httpClient.execute(buildRequest(uri).build());

    if (response.getStatusCode() == 404) {
      return ImmutableList.of();
    }

    checkResponse(type, response);
    LOG.debug("Got {} in {}ms", type, System.currentTimeMillis() - start);
    return response.getAs(typeReference);
  }

  private <T> void delete(String uri, String type, String id, Map<String, String> queryParams) {
    delete(uri, type, id, queryParams, Optional.<Class<T>>absent());
  }

  private <T> Optional<T> delete(String uri, String type, String id, Map<String, String> queryParams, Optional<Class<T>> clazz) {
    LOG.debug("Deleting {} {} from {}", type, id, uri);
    final long start = System.currentTimeMillis();
    HttpRequest.Builder request = buildRequest(uri, queryParams).setMethod(Method.DELETE);
    HttpResponse response = httpClient.execute(request.build());

    if (response.getStatusCode() == 404) {
      LOG.debug("{} ({}) was not found", type, id);
      return Optional.absent();
    }

    checkResponse(type, response);
    LOG.debug("Deleted {} ({}) from Baragon in %sms", type, id, System.currentTimeMillis() - start);

    if (clazz.isPresent()) {
      return Optional.of(response.getAs(clazz.get()));
    }

    return Optional.absent();
  }

  private <T> Optional<T> post(String uri, String type, Optional<?> body, Optional<Class<T>> clazz) {
    return post(uri, type, body, clazz, Collections.<String, String>emptyMap());
  }

  private <T> Optional<T> post(String uri, String type, Optional<?> body, Optional<Class<T>> clazz, Map<String, String> queryParams) {
    try {
      HttpResponse response = post(uri, type, body, queryParams);

      if (clazz.isPresent()) {
        return Optional.of(response.getAs(clazz.get()));
      }
    } catch (Exception e) {
      LOG.warn("Http post failed", e);
    }

    return Optional.absent();
  }

  private HttpResponse post(String uri, String type, Optional<?> body, Map<String, String> params) {
    LOG.debug("Posting {} to {}", type, uri);
    final long start = System.currentTimeMillis();
    HttpRequest.Builder request = buildRequest(uri, params).setMethod(Method.POST);

    if (body.isPresent()) {
      request.setBody(body.get());
    }

    HttpResponse response = httpClient.execute(request.build());
    checkResponse(type, response);
    LOG.debug("Successfully posted {} in {}ms", type, System.currentTimeMillis() - start);
    return response;
  }

  // BaragonService overall status

  public Optional<BaragonServiceStatus> getBaragonServiceStatus(String hostname) {
    final String uri = String.format(STATUS_FORMAT, hostname, contextPath);
    return getSingle(uri, "status", "", BaragonServiceStatus.class);
  }

  public Optional<BaragonServiceStatus> getAnyBaragonServiceStatus() {
    return getBaragonServiceStatus(getHost());
  }


  // BaragonService service states

  public Collection<BaragonServiceState> getGlobalState() {
    final String uri = String.format(STATE_FORMAT, getHost(), contextPath);
    return getCollection(uri, "global state", BARAGON_SERVICE_STATE_COLLECTION);
  }

  public Optional<BaragonServiceState> getServiceState(String serviceId) {
    final String uri = String.format(STATE_SERVICE_ID_FORMAT, getHost(), contextPath, serviceId);
    return getSingle(uri, "service state", serviceId, BaragonServiceState.class);
  }

  public Optional<BaragonResponse> deleteService(String serviceId) {
    final String uri = String.format(STATE_SERVICE_ID_FORMAT, getHost(), contextPath, serviceId);
    return delete(uri, "service state", serviceId, Collections.<String, String>emptyMap(), Optional.of(BaragonResponse.class));
  }

  public Optional<BaragonResponse> reloadServiceConfigs(String serviceId){
    final String uri = String.format(STATE_RELOAD_FORMAT, getHost(), contextPath, serviceId);
    return post(uri, "service reload",Optional.absent(), Optional.of(BaragonResponse.class));
  }


  // BaragonService Workers

  public Collection<String> getBaragonServiceWorkers() {
    final String requestUri = String.format(WORKERS_FORMAT, getHost(), contextPath);
    return getCollection(requestUri, "baragon service workers", STRING_COLLECTION);
  }


  // BaragonService load balancer group actions

  public Collection<String> getLoadBalancerGroups() {
    final String requestUri = String.format(LOAD_BALANCER_FORMAT, getHost(), contextPath);
    return getCollection(requestUri, "load balancer groups", STRING_COLLECTION);
  }

  public Collection<BaragonAgentMetadata> getLoadBalancerGroupAgentMetadata(String loadBalancerGroupName) {
    final String requestUri = String.format(LOAD_BALANCER_AGENTS_FORMAT, getHost(), contextPath, loadBalancerGroupName);
    return getCollection(requestUri, "load balancer agent metadata", BARAGON_AGENTS_COLLECTION);
  }

  public Collection<BaragonAgentMetadata> getLoadBalancerGroupKnownAgentMetadata(String loadBalancerGroupName) {
    final String requestUri = String.format(LOAD_BALANCER_KNOWN_AGENTS_FORMAT, getHost(), contextPath, loadBalancerGroupName);
    return getCollection(requestUri, "load balancer known agent metadata", BARAGON_AGENTS_COLLECTION);
  }

  public void deleteLoadBalancerGroupKnownAgent(String loadBalancerGroupName, String agentId) {
    final String requestUri = String.format(LOAD_BALANCER_DELETE_KNOWN_AGENT_FORMAT, getHost(), contextPath, loadBalancerGroupName, agentId);
    delete(requestUri, "known agent", agentId, Collections.<String, String>emptyMap());
  }

  public BaragonGroup addTrafficSource(String loadBalancerGroupName, String source) {
    final String requestUri = String.format(LOAD_BALANCER_TRAFFIC_SOURCE_FORMAT, getHost(), contextPath, loadBalancerGroupName);
    return post(requestUri, "add source", Optional.absent(), Optional.of(BaragonGroup.class), ImmutableMap.of("source", source)).get();
  }

  public Optional<BaragonGroup> removeTrafficSource(String loadBalancerGroupName, String source) {
    final String requestUri = String.format(LOAD_BALANCER_TRAFFIC_SOURCE_FORMAT, getHost(), contextPath, loadBalancerGroupName);
    return delete(requestUri, "remove source", source, ImmutableMap.of("source", source), Optional.of(BaragonGroup.class));
  }

  public Optional<BaragonGroup> getGroupDetail(String loadBalancerGroupName) {
    final String requestUri = String.format(LOAD_BALANCER_GROUP_FORMAT, getHost(), contextPath, loadBalancerGroupName);
    return getSingle(requestUri, "group detail", loadBalancerGroupName, BaragonGroup.class);
  }

  // BaragonService base path actions

  public Collection<String> getOccupiedBasePaths(String loadBalancerGroupName) {
    final String requestUri = String.format(LOAD_BALANCER_ALL_BASE_PATHS_FORMAT, getHost(), contextPath, loadBalancerGroupName);
    return getCollection(requestUri, "occupied base paths", STRING_COLLECTION);
  }

  public Optional<BaragonService> getServiceForBasePath(String loadBalancerGroupName, String basePath) {
    final String requestUri = String.format(LOAD_BALANCER_BASE_PATH_FORMAT, getHost(), contextPath, loadBalancerGroupName);
    return getSingle(requestUri, "service for base path", "", BaragonService.class, ImmutableMap.of("basePath", basePath));
  }

  public void clearBasePath(String loadBalancerGroupName, String basePath) {
    final String requestUri = String.format(LOAD_BALANCER_BASE_PATH_FORMAT, getHost(), contextPath, loadBalancerGroupName);
    delete(requestUri, "base path", "", ImmutableMap.of("basePath", basePath));
  }


  // BaragonService request actions

  public Optional<BaragonResponse> getRequest(String requestId) {
    final String uri = String.format(REQUEST_ID_FORMAT, getHost(), contextPath, requestId);
    return getSingle(uri, "request", requestId, BaragonResponse.class);
  }

  public Optional<BaragonResponse> enqueueRequest(BaragonRequest request) {
    final String uri = String.format(REQUEST_FORMAT, getHost(), contextPath);
    return post(uri, "request", Optional.of(request), Optional.of(BaragonResponse.class));
  }

  public Optional<BaragonResponse> cancelRequest(String requestId) {
    final String uri = String.format(REQUEST_ID_FORMAT, getHost(), contextPath, requestId);
    return delete(uri, "request", requestId, Collections.<String, String>emptyMap(), Optional.of(BaragonResponse.class));
  }


  // BaragonService queued request actions

  public Collection<QueuedRequestId> getQueuedRequests() {
    final String uri = String.format(REQUEST_FORMAT, getHost(), contextPath);
    return getCollection(uri, "queued requests", QUEUED_REQUEST_COLLECTION);
  }
}
