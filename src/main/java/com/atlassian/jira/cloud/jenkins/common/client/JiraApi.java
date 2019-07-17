package com.atlassian.jira.cloud.jenkins.common.client;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.io.NotSerializableException;
import java.util.Objects;
import java.util.Optional;

/** Common client to talk to Build & Deployment APIs in Jira */
public class JiraApi {

    private static final Logger log = LoggerFactory.getLogger(JiraApi.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String apiEndpoint;

    @Inject
    public JiraApi(
            final OkHttpClient httpClient, final ObjectMapper objectMapper, final String apiUrl) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.apiEndpoint = apiUrl;
    }

    /**
     * Submits an update to the Atlassian Builds or Deployments API and returns the response
     *
     * @param cloudId Jira Cloud Id
     * @param accessToken Access token generated from Atlassian API
     * @param jiraSiteUrl Jira site URL
     * @param jiraRequest An assembled payload to be submitted to Jira
     * @return Response from the API
     */
    public <ResponseEntity> Optional<ResponseEntity> postUpdate(
            final String cloudId,
            final String accessToken,
            final String jiraSiteUrl,
            final JiraRequest jiraRequest,
            final Class<ResponseEntity> responseClass) {
        try {
            final String requestPayload = objectMapper.writeValueAsString(jiraRequest);
            final Request request = getRequest(cloudId, accessToken, requestPayload);
            final Response response = httpClient.newCall(request).execute();

            checkForErrorResponse(jiraSiteUrl, response);
            return Optional.ofNullable(handleResponseBody(jiraSiteUrl, response, responseClass));
        } catch (NotSerializableException e) {
            handleError(String.format("Empty body when submitting to %s", jiraSiteUrl));
        } catch (JsonMappingException | JsonParseException e) {
            handleError(String.format("Invalid JSON when submitting to %s", jiraSiteUrl));
        } catch (JsonProcessingException e) {
            handleError(
                    String.format(
                            "Unable to create the request payload for %s : %s",
                            jiraSiteUrl, e.getMessage()));
        } catch (IOException e) {
            handleError(
                    String.format(
                            "Server exception when submitting to %s: %s",
                            jiraSiteUrl, e.getMessage()));
        }

        return Optional.empty();
    }

    private void checkForErrorResponse(final String jiraSiteUrl, final Response response)
            throws IOException {
        if (!response.isSuccessful()) {
            final String message =
                    String.format(
                            "Error response code %d when submitting to %s",
                            response.code(), jiraSiteUrl);
            final ResponseBody responseBody = response.body();
            if (responseBody != null) {
                log.error(
                        String.format(
                                "Error response body when submitting to %s: %s",
                                jiraSiteUrl, responseBody.string()));
            }

            handleError(message);
        }
    }

    private <ResponseEntity> ResponseEntity handleResponseBody(
            final String jiraSiteUrl,
            final Response response,
            final Class<ResponseEntity> responseClass)
            throws IOException {
        if (response.body() == null) {
            final String message =
                    String.format("Empty response body when submitting to %s", jiraSiteUrl);
            handleError(message);
        }

        return objectMapper.readValue(
                response.body().bytes(),
                objectMapper.getTypeFactory().constructType(responseClass));
    }

    @VisibleForTesting
    void setApiEndpoint(final String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    private Request getRequest(
            final String cloudId, final String accessToken, final String requestPayload) {
        RequestBody body = RequestBody.create(JSON, requestPayload);
        return new Request.Builder()
                .url(String.format(this.apiEndpoint, cloudId))
                .addHeader("Authorization", "Bearer " + accessToken)
                .post(body)
                .build();
    }

    private void handleError(final String message) {
        log.error(message);
        throw new ApiUpdateFailedException(message);
    }
}
