/*
 * Copyright 2013-2017 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.client;

import com.erudika.para.core.App;
import com.erudika.para.core.ParaObject;
import com.erudika.para.core.Tag;
import com.erudika.para.core.User;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.rest.GenericExceptionMapper;
import com.erudika.para.rest.Signer;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import com.erudika.para.validation.Constraint;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.*;

import static javax.ws.rs.HttpMethod.*;

/**
 * The Java REST client for communicating with a Para API server.
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class ParaClient {

    private static final Logger logger = LoggerFactory.getLogger(ParaClient.class);
    private static final String DEFAULT_ENDPOINT = "https://paraio.com";
    private static final String DEFAULT_PATH = "/v1/";
    private static final String JWT_PATH = "/jwt_auth";
    private String endpoint;
    private String path;
    private String accessKey;
    private String secretKey;
    private String tokenKey;
    private Long tokenKeyExpires;
    private Long tokenKeyNextRefresh;
    private Client apiClient;
    private final Signer signer = new Signer();

    /**
     * Default constructor.
     *
     * @param accessKey app access key
     * @param secretKey app secret key
     */
    public ParaClient(String accessKey, String secretKey) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        if (StringUtils.length(secretKey) < 6) {
            logger.warn("Secret key appears to be invalid. Make sure you call 'signIn()' first.");
        }
        ClientConfig clientConfig = new ClientConfig();

        clientConfig.register(GenericExceptionMapper.class);
        clientConfig.register(new JacksonJsonProvider(ParaObjectUtils.getJsonMapper()));
        clientConfig.connectorProvider(new HttpUrlConnectorProvider().useSetMethodWorkaround());
//        SSLContext sslContext = SslConfigurator.newInstance().securityProtocol("TLSv1").createSSLContext();
//        System.setProperty("https.protocols", "TLSv1");
        // add some text
        apiClient = ClientBuilder.newBuilder().
                withConfig(clientConfig).build();

        apiClient.property(ClientProperties.PROXY_URI, "127.0.0.1:8888");
    }

    protected Client getApiClient() {
        return apiClient;
    }

    protected void setApiClient(Client apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Sets the host URL of the Para server.
     *
     * @param endpoint the Para server location
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Closes the underlying Jersey client and releases resources.
     */
    public void close() {
        if (apiClient != null) {
            apiClient.close();
        }
    }

    /**
     * Returns the {@link App} for the current access key (appid).
     *
     * @return the App object
     */
    public App getApp() {
        return me();
    }

    /**
     * Returns the endpoint URL.
     *
     * @return the endpoint
     */
    public String getEndpoint() {
        if (StringUtils.isBlank(endpoint)) {
            return DEFAULT_ENDPOINT;
        } else {
            return endpoint;
        }
    }

    /**
     * Sets the API request path.
     *
     * @param path a new path
     */
    public void setApiPath(String path) {
        this.path = path;
    }

    /**
     * Returns the API request path.
     *
     * @return the request path without parameters
     */
    public String getApiPath() {
        if (StringUtils.isBlank(path)) {
            return DEFAULT_PATH;
        } else {
            if (!path.endsWith("/")) {
                path += "/";
            }
            return path;
        }
    }

    /**
     * @return the JWT access token, or null if not signed in
     */
    public String getAccessToken() {
        return tokenKey;
    }

    /**
     * @return the version of Para server
     */
    public String getServerVersion() {
        Map<String, Object> res = getEntity(invokeGet("", null), Map.class);
        if (res == null || StringUtils.isBlank((String) res.get("version"))) {
            return "unknown";
        } else {
            return (String) res.get("version");
        }
    }

    /**
     * Sets the JWT access token.
     *
     * @param token a valid token
     */
    @SuppressWarnings("unchecked")
    public void setAccessToken(String token) {
        if (!StringUtils.isBlank(token)) {
            try {
                String payload = Utils.base64dec(StringUtils.substringBetween(token, ".", "."));
                Map<String, Object> decoded = ParaObjectUtils.getJsonMapper().readValue(payload, Map.class);
                if (decoded != null && decoded.containsKey("exp")) {
                    this.tokenKeyExpires = (Long) decoded.get("exp");
                    this.tokenKeyNextRefresh = (Long) decoded.get("refresh");
                }
            } catch (Exception ex) {
                this.tokenKeyExpires = null;
                this.tokenKeyNextRefresh = null;
            }
        }
        this.tokenKey = token;
    }

    /**
     * Clears the JWT token from memory, if such exists.
     */
    private void clearAccessToken() {
        tokenKey = null;
        tokenKeyExpires = null;
        tokenKeyNextRefresh = null;
    }

    private String key(boolean refresh) {
        if (tokenKey != null) {
            if (refresh) {
                refreshToken();
            }
            return "Bearer " + tokenKey;
        }
        return secretKey;
    }

    @SuppressWarnings("unchecked")
    private <T> T getEntity(Response res, Class<?> type) {
        if (res != null) {
            if (res.getStatus() == Response.Status.OK.getStatusCode()
                    || res.getStatus() == Response.Status.CREATED.getStatusCode()
                    || res.getStatus() == Response.Status.NOT_MODIFIED.getStatusCode()) {
                return res.hasEntity() ? res.readEntity((Class<T>) type) : null;
            } else if (res.getStatus() != Response.Status.NOT_FOUND.getStatusCode()
                    && res.getStatus() != Response.Status.NOT_MODIFIED.getStatusCode()
                    && res.getStatus() != Response.Status.NO_CONTENT.getStatusCode()) {
                Map<String, Object> error = res.hasEntity() ? res.readEntity(Map.class) : null;
                if (error != null && error.containsKey("code")) {
                    String msg = error.containsKey("message") ? (String) error.get("message") : "error";
                    logger.error("{} - {}", msg, new WebApplicationException((Integer) error.get("code")).getMessage());
                }
            }
        }
        return null;
    }

    private String getFullPath(String resourcePath) {
        if (StringUtils.startsWith(resourcePath, JWT_PATH)) {
            return resourcePath;
        }
        if (resourcePath == null) {
            resourcePath = "";
        } else if (resourcePath.startsWith("/")) {
            resourcePath = resourcePath.substring(1);
        }
        return getApiPath() + resourcePath;
    }

    private Response invokeGet(String resourcePath, MultivaluedMap<String, String> params) {
        logger.debug("GET {}, params: {}", getFullPath(resourcePath), params);
        return signer.invokeSignedRequest(getApiClient(), accessKey, key(!JWT_PATH.equals(resourcePath)), GET,
                getEndpoint(), getFullPath(resourcePath), null, params, new byte[0]);
    }

    private Response invokePost(String resourcePath, Entity<?> entity) {
        logger.debug("POST {}, entity: {}", getFullPath(resourcePath), entity);
        return signer.invokeSignedRequest(getApiClient(), accessKey, key(true), POST,
                getEndpoint(), getFullPath(resourcePath), null, null, entity);
    }

    private Response invokePut(String resourcePath, Entity<?> entity) {
        logger.debug("PUT {}, entity: {}", getFullPath(resourcePath), entity);
        return signer.invokeSignedRequest(getApiClient(), accessKey, key(true), PUT,
                getEndpoint(), getFullPath(resourcePath), null, null, entity);
    }

    private Response invokePatch(String resourcePath, Entity<?> entity) {
        logger.debug("PATCH {}, entity: {}", getFullPath(resourcePath), entity);
        return signer.invokeSignedRequest(getApiClient(), accessKey, key(true), "PATCH",
                getEndpoint(), getFullPath(resourcePath), null, null, entity);
    }

    private Response invokeDelete(String resourcePath, MultivaluedMap<String, String> params) {
        logger.debug("DELETE {}, params: {}", getFullPath(resourcePath), params);
        return signer.invokeSignedRequest(getApiClient(), accessKey, key(true), DELETE,
                getEndpoint(), getFullPath(resourcePath), null, params, new byte[0]);
    }

    private MultivaluedMap<String, String> pagerToParams(Pager... pager) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        if (pager != null && pager.length > 0) {
            Pager p = pager[0];
            if (p != null) {
                map.put("page", Collections.singletonList(Long.toString(p.getPage())));
                map.put("desc", Collections.singletonList(Boolean.toString(p.isDesc())));
                map.put("limit", Collections.singletonList(Integer.toString(p.getLimit())));
                if (p.getSortby() != null) {
                    map.put("sort", Collections.singletonList(p.getSortby()));
                }
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private <P extends ParaObject> List<P> getItemsFromList(List<?> result) {
        if (result != null && !result.isEmpty()) {
            // this isn't very efficient but there's no way to know what type of objects we're reading
            ArrayList<P> objects = new ArrayList<>(result.size());
            for (Object map : result) {
                P p = ParaObjectUtils.setAnnotatedFields((Map<String, Object>) map);
                if (p != null) {
                    objects.add(p);
                }
            }
            return objects;
        }
        return Collections.emptyList();
    }

    private <P extends ParaObject> List<P> getItems(Map<String, Object> result, Pager... pager) {
        if (result != null && !result.isEmpty() && result.containsKey("items")) {
            if (pager != null && pager.length > 0 && pager[0] != null && result.containsKey("totalHits")) {
                pager[0].setCount(((Integer) result.get("totalHits")).longValue());
            }
            return getItemsFromList((List<?>) result.get("items"));
        }
        return Collections.emptyList();
    }

    /////////////////////////////////////////////
    //				 PERSISTENCE
    /////////////////////////////////////////////

    /**
     * Persists an object to the data store. If the object's type and id are given,
     * then the request will be a {@code PUT} request and any existing object will be
     * overwritten.
     *
     * @param <P> the type of object
     * @param obj the domain object
     * @return the same object with assigned id or null if not created.
     */
    public <P extends ParaObject> P create(P obj) {
        if (obj == null) {
            return null;
        }
        if (StringUtils.isBlank(obj.getId()) || StringUtils.isBlank(obj.getType())) {
            return getEntity(invokePost(obj.getType(), Entity.json(obj)), obj.getClass());
        } else {
            return getEntity(invokePut(obj.getType().concat("/").concat(obj.getId()), Entity.json(obj)), obj.getClass());
        }
    }

    /**
     * Retrieves an object from the data store.
     *
     * @param <P>  the type of object
     * @param type the type of the object
     * @param id   the id of the object
     * @return the retrieved object or null if not found
     */
    public <P extends ParaObject> P read(String type, String id) {
        if (StringUtils.isBlank(type) || StringUtils.isBlank(id)) {
            return null;
        }

        return getEntity(invokeGet(type.concat("/").concat(id), null), ParaObjectUtils.toClass(type));
    }

    /**
     * Retrieves an object from the data store.
     *
     * @param <P> the type of object
     * @param id  the id of the object
     * @return the retrieved object or null if not found
     */
    public <P extends ParaObject> P read(String id) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        Map<String, Object> data = getEntity(invokeGet("_id/".concat(id), null), Map.class);
        return ParaObjectUtils.setAnnotatedFields(data);
    }

    /**
     * Updates an object permanently. Supports partial updates.
     *
     * @param <P> the type of object
     * @param obj the object to update
     * @return the updated object
     */
    public <P extends ParaObject> P update(P obj) {
        if (obj == null) {
            return null;
        }
        return getEntity(invokePatch(obj.getType().concat("/").concat(obj.getId()), Entity.json(obj)), obj.getClass());
    }

    /**
     * Deletes an object permanently.
     *
     * @param <P> the type of object
     * @param obj the object
     */
    public <P extends ParaObject> void delete(P obj) {
        if (obj == null || obj.getId() == null) {
            return;
        }
        invokeDelete(obj.getType().concat("/").concat(obj.getId()), null);
    }

    /**
     * Saves multiple objects to the data store.
     *
     * @param <P>     the type of object
     * @param objects the list of objects to save
     * @return a list of objects
     */
    public <P extends ParaObject> List<P> createAll(List<P> objects) {
        if (objects == null || objects.isEmpty() || objects.get(0) == null) {
            return Collections.emptyList();
        }
        return getItemsFromList((List<?>) getEntity(invokePost("_batch", Entity.json(objects)), List.class));
    }

    /**
     * Retrieves multiple objects from the data store.
     *
     * @param <P>  the type of object
     * @param keys a list of object ids
     * @return a list of objects
     */
    public <P extends ParaObject> List<P> readAll(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        MultivaluedMap<String, String> ids = new MultivaluedHashMap<>();
        ids.put("ids", keys);
        return getItemsFromList((List<?>) getEntity(invokeGet("_batch", ids), List.class));
    }

    /**
     * Updates multiple objects.
     *
     * @param <P>     the type of object
     * @param objects the objects to update
     * @return a list of objects
     */
    public <P extends ParaObject> List<P> updateAll(List<P> objects) {
        if (objects == null || objects.isEmpty()) {
            return Collections.emptyList();
        }
        return getItemsFromList((List<?>) getEntity(invokePatch("_batch", Entity.json(objects)), List.class));
    }

    /**
     * Deletes multiple objects.
     *
     * @param keys the ids of the objects to delete
     */
    public void deleteAll(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        MultivaluedMap<String, String> ids = new MultivaluedHashMap<>();
        ids.put("ids", keys);
        invokeDelete("_batch", ids);
    }

    /**
     * Returns a list all objects found for the given type.
     * The result is paginated so only one page of items is returned, at a time.
     *
     * @param <P>   the type of object
     * @param type  the type of objects to search for
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of objects
     */
    @SuppressWarnings("unchecked")
    public <P extends ParaObject> List<P> list(String type, Pager... pager) {
        if (StringUtils.isBlank(type)) {
            return Collections.emptyList();
        }
        return getItems((Map<String, Object>) getEntity(invokeGet(type, pagerToParams(pager)), Map.class), pager);
    }

    /////////////////////////////////////////////
    //				 SEARCH
    /////////////////////////////////////////////

    /**
     * Simple id search.
     *
     * @param <P> type of the object
     * @param id  the id
     * @return the object if found or null
     */
    public <P extends ParaObject> P findById(String id) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle(Config._ID, id);
        List<P> list = getItems(find("id", params));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Simple multi id search.
     *
     * @param <P> type of the object
     * @param ids a list of ids to search for
     * @return a list of object found
     */
    public <P extends ParaObject> List<P> findByIds(List<String> ids) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.put("ids", ids);
        return getItems(find("ids", params));
    }

    /**
     * Search for {@link com.erudika.para.core.Address} objects in a radius of X km from a given point.
     *
     * @param <P>    type of the object
     * @param type   the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param query  the query string
     * @param radius the radius of the search circle
     * @param lat    latitude
     * @param lng    longitude
     * @param pager  a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findNearby(String type, String query, int radius, double lat, double lng,
                                                     Pager... pager) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("latlng", lat + "," + lng);
        params.putSingle("radius", Integer.toString(radius));
        params.putSingle("q", query);
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("nearby", params), pager);
    }

    /**
     * Searches for objects that have a property which value starts with a given prefix.
     *
     * @param <P>    type of the object
     * @param type   the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param field  the property name of an object
     * @param prefix the prefix
     * @param pager  a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findPrefix(String type, String field, String prefix, Pager... pager) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("field", field);
        params.putSingle("prefix", prefix);
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("prefix", params), pager);
    }

    /**
     * Simple query string search. This is the basic search method.
     *
     * @param <P>   type of the object
     * @param type  the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param query the query string
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findQuery(String type, String query, Pager... pager) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("q", query);
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("", params), pager);
    }

    /**
     * Searches within a nested field. The objects of the given type must contain a nested field "nstd".
     *
     * @param <P>   type of the object
     * @param type  the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param field the name of the field to target (within a nested field "nstd")
     * @param query the query string
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findNestedQuery(String type, String field, String query, Pager... pager) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("q", query);
        params.putSingle("field", field);
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("nested", params), pager);
    }

    /**
     * Searches for objects that have similar property values to a given text. A "find like this" query.
     *
     * @param <P>       type of the object
     * @param type      the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param filterKey exclude an object with this key from the results (optional)
     * @param fields    a list of property names
     * @param liketext  text to compare to
     * @param pager     a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findSimilar(String type, String filterKey, String[] fields, String liketext,
                                                      Pager... pager) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.put("fields", fields == null ? null : Arrays.asList(fields));
        params.putSingle("filterid", filterKey);
        params.putSingle("like", liketext);
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("similar", params), pager);
    }

    /**
     * Searches for objects tagged with one or more tags.
     *
     * @param <P>   type of the object
     * @param type  the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param tags  the list of tags
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findTagged(String type, String[] tags, Pager... pager) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.put("tags", tags == null ? null : Arrays.asList(tags));
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("tagged", params), pager);
    }

    /**
     * Searches for {@link com.erudika.para.core.Tag} objects.
     * This method might be deprecated in the future.
     *
     * @param <P>     type of the object
     * @param keyword the tag keyword to search for
     * @param pager   a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findTags(String keyword, Pager... pager) {
        keyword = (keyword == null) ? "*" : keyword.concat("*");
        return findWildcard(Utils.type(Tag.class), "tag", keyword, pager);
    }

    /**
     * Searches for objects having a property value that is in list of possible values.
     *
     * @param <P>   type of the object
     * @param type  the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param field the property name of an object
     * @param terms a list of terms (property values)
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findTermInList(String type, String field, List<String> terms, Pager... pager) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("field", field);
        params.put("terms", terms);
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("in", params), pager);
    }

    /**
     * Searches for objects that have properties matching some given values. A terms query.
     *
     * @param <P>      type of the object
     * @param type     the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param terms    a map of fields (property names) to terms (property values)
     * @param matchAll match all terms. If true - AND search, if false - OR search
     * @param pager    a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findTerms(String type, Map<String, ?> terms, boolean matchAll,
                                                    Pager... pager) {
        if (terms == null) {
            return Collections.emptyList();
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("matchall", Boolean.toString(matchAll));
        LinkedList<String> list = new LinkedList<>();
        for (Map.Entry<String, ? extends Object> term : terms.entrySet()) {
            String key = term.getKey();
            Object value = term.getValue();
            if (value != null) {
                list.add(key.concat(Config.SEPARATOR).concat(value.toString()));
            }
        }
        if (!terms.isEmpty()) {
            params.put("terms", list);
        }
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("terms", params), pager);
    }

    /**
     * Searches for objects that have a property with a value matching a wildcard query.
     *
     * @param <P>      type of the object
     * @param type     the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param field    the property name of an object
     * @param wildcard wildcard query string. For example "cat*".
     * @param pager    a {@link com.erudika.para.utils.Pager}
     * @return a list of objects found
     */
    public <P extends ParaObject> List<P> findWildcard(String type, String field, String wildcard, Pager... pager) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("field", field);
        params.putSingle("q", wildcard);
        params.putSingle(Config._TYPE, type);
        params.putAll(pagerToParams(pager));
        return getItems(find("wildcard", params), pager);
    }

    /**
     * Counts indexed objects.
     *
     * @param type the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @return the number of results found
     */
    public Long getCount(String type) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle(Config._TYPE, type);
        Pager pager = new Pager();
        getItems(find("count", params), pager);
        return pager.getCount();
    }

    /**
     * Counts indexed objects matching a set of terms/values.
     *
     * @param type  the type of object to search for. See {@link com.erudika.para.core.ParaObject#getType()}
     * @param terms a list of terms (property values)
     * @return the number of results found
     */
    public Long getCount(String type, Map<String, ?> terms) {
        if (terms == null) {
            return 0L;
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        LinkedList<String> list = new LinkedList<>();
        for (Map.Entry<String, ? extends Object> term : terms.entrySet()) {
            String key = term.getKey();
            Object value = term.getValue();
            if (value != null) {
                list.add(key.concat(Config.SEPARATOR).concat(value.toString()));
            }
        }
        if (!terms.isEmpty()) {
            params.put("terms", list);
        }
        params.putSingle(Config._TYPE, type);
        params.putSingle("count", "true");
        Pager pager = new Pager();
        getItems(find("terms", params), pager);
        return pager.getCount();
    }

    private Map<String, Object> find(String queryType, MultivaluedMap<String, String> params) {
        Map<String, Object> map = new HashMap<>();
        if (params != null && !params.isEmpty()) {
            String qType = StringUtils.isBlank(queryType) ? "/default" : "/".concat(queryType);
            if (StringUtils.isBlank(params.getFirst(Config._TYPE))) {
                return getEntity(invokeGet("search" + qType, params), Map.class);
            } else {
                return getEntity(invokeGet(params.getFirst(Config._TYPE) + "/search" + qType, params), Map.class);
            }
        } else {
            map.put("items", Collections.emptyList());
            map.put("totalHits", 0);
        }
        return map;
    }

    /////////////////////////////////////////////
    //				 LINKS
    /////////////////////////////////////////////

    /**
     * Count the total number of links between this object and another type of object.
     *
     * @param type2 the other type of object
     * @param obj   the object to execute this method on
     * @return the number of links for the given object
     */
    @SuppressWarnings("unchecked")
    public Long countLinks(ParaObject obj, String type2) {
        if (obj == null || obj.getId() == null || type2 == null) {
            return 0L;
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("count", "true");
        Pager pager = new Pager();
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), type2);
        getItems((Map<String, Object>) getEntity(invokeGet(url, params), Map.class), pager);
        return pager.getCount();
    }

    /**
     * Returns all objects linked to the given one. Only applicable to many-to-many relationships.
     *
     * @param <P>   type of linked objects
     * @param type2 type of linked objects to search for
     * @param obj   the object to execute this method on
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of linked objects
     */
    @SuppressWarnings("unchecked")
    public <P extends ParaObject> List<P> getLinkedObjects(ParaObject obj, String type2, Pager... pager) {
        if (obj == null || obj.getId() == null || type2 == null) {
            return Collections.emptyList();
        }
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), type2);
        return getItems((Map<String, Object>) getEntity(invokeGet(url, pagerToParams(pager)), Map.class), pager);
    }

    /**
     * Searches through all linked objects in many-to-many relationships.
     *
     * @param <P>   type of linked objects
     * @param type2 type of linked objects to search for
     * @param obj   the object to execute this method on
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @param field the name of the field to target (within a nested field "nstd")
     * @param query a query string
     * @return a list of linked objects matching the search query
     */
    @SuppressWarnings("unchecked")
    public <P extends ParaObject> List<P> findLinkedObjects(ParaObject obj, String type2, String field,
                                                            String query, Pager... pager) {
        if (obj == null || obj.getId() == null || type2 == null) {
            return Collections.emptyList();
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("field", field);
        params.putSingle("q", (query == null) ? "*" : query);
        params.putAll(pagerToParams(pager));
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), type2);
        return getItems((Map<String, Object>) getEntity(invokeGet(url, params), Map.class), pager);
    }

    /**
     * Checks if this object is linked to another.
     *
     * @param type2 the other type
     * @param id2   the other id
     * @param obj   the object to execute this method on
     * @return true if the two are linked
     */
    public boolean isLinked(ParaObject obj, String type2, String id2) {
        if (obj == null || obj.getId() == null || type2 == null || id2 == null) {
            return false;
        }
        String url = Utils.formatMessage("{0}/links/{1}/{2}", obj.getObjectURI(), type2, id2);
        Boolean result = getEntity(invokeGet(url, null), Boolean.class);
        return result != null && result;
    }

    /**
     * Checks if a given object is linked to this one.
     *
     * @param toObj the other object
     * @param obj   the object to execute this method on
     * @return true if linked
     */
    public boolean isLinked(ParaObject obj, ParaObject toObj) {
        if (obj == null || obj.getId() == null || toObj == null || toObj.getId() == null) {
            return false;
        }
        return isLinked(obj, toObj.getType(), toObj.getId());
    }

    /**
     * Links an object to this one in a many-to-many relationship.
     * Only a link is created. Objects are left untouched.
     * The type of the second object is automatically determined on read.
     *
     * @param id2 link to the object with this id
     * @param obj the object to execute this method on
     * @return the id of the {@link com.erudika.para.core.Linker} object that is created
     */
    public String link(ParaObject obj, String id2) {
        if (obj == null || obj.getId() == null || id2 == null) {
            return null;
        }
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), id2);
        return getEntity(invokePost(url, null), String.class);
    }

    /**
     * Unlinks an object from this one.
     * Only a link is deleted. Objects are left untouched.
     *
     * @param type2 the other type
     * @param obj   the object to execute this method on
     * @param id2   the other id
     */
    public void unlink(ParaObject obj, String type2, String id2) {
        if (obj == null || obj.getId() == null || type2 == null || id2 == null) {
            return;
        }
        String url = Utils.formatMessage("{0}/links/{1}/{2}", obj.getObjectURI(), type2, id2);
        invokeDelete(url, null);
    }

    /**
     * Unlinks all objects that are linked to this one.
     *
     * @param obj the object to execute this method on
     *            Only {@link com.erudika.para.core.Linker} objects are deleted.
     *            {@link com.erudika.para.core.ParaObject}s are left untouched.
     */
    public void unlinkAll(ParaObject obj) {
        if (obj == null || obj.getId() == null) {
            return;
        }
        String url = Utils.formatMessage("{0}/links", obj.getObjectURI());
        invokeDelete(url, null);
    }

    /**
     * Count the total number of child objects for this object.
     *
     * @param type2 the type of the other object
     * @param obj   the object to execute this method on
     * @return the number of links
     */
    @SuppressWarnings("unchecked")
    public Long countChildren(ParaObject obj, String type2) {
        if (obj == null || obj.getId() == null || type2 == null) {
            return 0L;
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("count", "true");
        params.putSingle("childrenonly", "true");
        Pager pager = new Pager();
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), type2);
        getItems((Map<String, Object>) getEntity(invokeGet(url, params), Map.class), pager);
        return pager.getCount();
    }

    /**
     * Returns all child objects linked to this object.
     *
     * @param <P>   the type of children
     * @param type2 the type of children to look for
     * @param obj   the object to execute this method on
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of {@link ParaObject} in a one-to-many relationship with this object
     */
    @SuppressWarnings("unchecked")
    public <P extends ParaObject> List<P> getChildren(ParaObject obj, String type2, Pager... pager) {
        if (obj == null || obj.getId() == null || type2 == null) {
            return Collections.emptyList();
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("childrenonly", "true");
        params.putAll(pagerToParams(pager));
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), type2);
        return getItems((Map<String, Object>) getEntity(invokeGet(url, params), Map.class), pager);
    }

    /**
     * Returns all child objects linked to this object.
     *
     * @param <P>   the type of children
     * @param type2 the type of children to look for
     * @param field the field name to use as filter
     * @param term  the field value to use as filter
     * @param obj   the object to execute this method on
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of {@link ParaObject} in a one-to-many relationship with this object
     */
    @SuppressWarnings("unchecked")
    public <P extends ParaObject> List<P> getChildren(ParaObject obj, String type2, String field, String term,
                                                      Pager... pager) {
        if (obj == null || obj.getId() == null || type2 == null) {
            return Collections.emptyList();
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("childrenonly", "true");
        params.putSingle("field", field);
        params.putSingle("term", term);
        params.putAll(pagerToParams(pager));
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), type2);
        return getItems((Map<String, Object>) getEntity(invokeGet(url, params), Map.class), pager);
    }

    /**
     * Search through all child objects. Only searches child objects directly
     * connected to this parent via the {@code parentid} field.
     *
     * @param <P>   the type of children
     * @param type2 the type of children to look for
     * @param query a query string
     * @param obj   the object to execute this method on
     * @param pager a {@link com.erudika.para.utils.Pager}
     * @return a list of {@link ParaObject} in a one-to-many relationship with this object
     */
    @SuppressWarnings("unchecked")
    public <P extends ParaObject> List<P> findChildren(ParaObject obj, String type2, String query, Pager... pager) {
        if (obj == null || obj.getId() == null || type2 == null) {
            return Collections.emptyList();
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("childrenonly", "true");
        params.putSingle("q", (query == null) ? "*" : query);
        params.putAll(pagerToParams(pager));
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), type2);
        return getItems((Map<String, Object>) getEntity(invokeGet(url, params), Map.class), pager);
    }

    /**
     * Deletes all child objects permanently.
     *
     * @param obj   the object to execute this method on
     * @param type2 the children's type.
     */
    public void deleteChildren(ParaObject obj, String type2) {
        if (obj == null || obj.getId() == null || type2 == null) {
            return;
        }
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("childrenonly", "true");
        String url = Utils.formatMessage("{0}/links/{1}", obj.getObjectURI(), type2);
        invokeDelete(url, params);
    }

    /////////////////////////////////////////////
    //				 UTILS
    /////////////////////////////////////////////

    /**
     * Generates a new unique id.
     *
     * @return a new id
     */
    public String newId() {
        String res = getEntity(invokeGet("utils/newid", null), String.class);
        return res != null ? res : "";
    }

    /**
     * Returns the current timestamp.
     *
     * @return a long number
     */
    public long getTimestamp() {
        Long res = getEntity(invokeGet("utils/timestamp", null), Long.class);
        return res != null ? res : 0L;
    }

    /**
     * Formats a date in a specific format.
     *
     * @param format the date format
     * @param loc    the locale instance
     * @return a formatted date
     */
    public String formatDate(String format, Locale loc) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("format", format);
        params.putSingle("locale", loc == null ? null : loc.toString());
        return getEntity(invokeGet("utils/formatdate", params), String.class);
    }

    /**
     * Converts spaces to dashes.
     *
     * @param str         a string with spaces
     * @param replaceWith a string to replace spaces with
     * @return a string with dashes
     */
    public String noSpaces(String str, String replaceWith) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("string", str);
        params.putSingle("replacement", replaceWith);
        return getEntity(invokeGet("utils/nospaces", params), String.class);
    }

    /**
     * Strips all symbols, punctuation, whitespace and control chars from a string.
     *
     * @param str a dirty string
     * @return a clean string
     */
    public String stripAndTrim(String str) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("string", str);
        return getEntity(invokeGet("utils/nosymbols", params), String.class);
    }

    /**
     * Converts Markdown to HTML.
     *
     * @param markdownString Markdown
     * @return HTML
     */
    public String markdownToHtml(String markdownString) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("md", markdownString);
        return getEntity(invokeGet("utils/md2html", params), String.class);
    }

    /**
     * Returns the number of minutes, hours, months elapsed for a time delta (milliseconds).
     *
     * @param delta the time delta between two events, in milliseconds
     * @return a string like "5m", "1h"
     */
    public String approximately(long delta) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("delta", Long.toString(delta));
        return getEntity(invokeGet("utils/timeago", params), String.class);
    }

    /////////////////////////////////////////////
    //				 MISC
    /////////////////////////////////////////////

    /**
     * Generates a new set of access/secret keys.
     * Old keys are discarded and invalid after this.
     *
     * @return a map of new credentials
     */
    public Map<String, String> newKeys() {
        Map<String, String> keys = getEntity(invokePost("_newkeys", null), Map.class);
        if (keys != null && keys.containsKey("secretKey")) {
            this.secretKey = keys.get("secretKey");
        }
        return keys;
    }

    /**
     * Returns all registered types for this App.
     *
     * @return a map of plural-singular form of all the registered types.
     */
    public Map<String, String> types() {
        return getEntity(invokeGet("_types", null), Map.class);
    }

    /**
     * Returns a {@link com.erudika.para.core.User} or an
     * {@link com.erudika.para.core.App} that is currently authenticated.
     *
     * @param <P> an App or User
     * @return a {@link com.erudika.para.core.User} or an {@link com.erudika.para.core.App}
     */
    public <P extends ParaObject> P me() {
        Map<String, Object> data = getEntity(invokeGet("_me", null), Map.class);
        return ParaObjectUtils.setAnnotatedFields(data);
    }

    /**
     * Verifies a given JWT and returns the authenticated subject. This request will not remember the JWT in memory.
     *
     * @param <P>         an App or User
     * @param accessToken a valid JWT access token
     * @return a {@link com.erudika.para.core.User} or an {@link com.erudika.para.core.App}
     */
    public <P extends ParaObject> P me(String accessToken) {
        if (!StringUtils.isBlank(accessToken)) {
            String auth = accessToken.startsWith("Bearer") ? accessToken : "Bearer " + accessToken;
            Response res = signer.invokeSignedRequest(getApiClient(), accessKey, auth, GET,
                    getEndpoint(), getFullPath("_me"), null, null, new byte[0]);
            Map<String, Object> data = getEntity(res, Map.class);
            return ParaObjectUtils.setAnnotatedFields(data);
        }
        return me();
    }

    /**
     * Upvote an object and register the vote in DB.
     *
     * @param obj     the object to receive +1 votes
     * @param voterid the userid of the voter
     * @return true if vote was successful
     */
    public boolean voteUp(ParaObject obj, String voterid) {
        if (obj == null || StringUtils.isBlank(voterid)) {
            return false;
        }
        return getEntity(invokePatch(obj.getType().concat("/").concat(obj.getId()),
                Entity.json(Collections.singletonMap("_voteup", voterid))), Boolean.class);
    }

    /**
     * Downvote an object and register the vote in DB.
     *
     * @param obj     the object to receive -1 votes
     * @param voterid the userid of the voter
     * @return true if vote was successful
     */
    public boolean voteDown(ParaObject obj, String voterid) {
        if (obj == null || StringUtils.isBlank(voterid)) {
            return false;
        }
        return getEntity(invokePatch(obj.getType().concat("/").concat(obj.getId()),
                Entity.json(Collections.singletonMap("_votedown", voterid))), Boolean.class);
    }

    /////////////////////////////////////////////
    //			Validation Constraints
    /////////////////////////////////////////////

    /**
     * Returns the validation constraints map.
     *
     * @return a map containing all validation constraints.
     */
    public Map<String, Map<String, Map<String, Map<String, ?>>>> validationConstraints() {
        return getEntity(invokeGet("_constraints", null), Map.class);
    }

    /**
     * Returns the validation constraints map.
     *
     * @param type a type
     * @return a map containing all validation constraints for this type.
     */
    public Map<String, Map<String, Map<String, Map<String, ?>>>> validationConstraints(String type) {
        return getEntity(invokeGet(Utils.formatMessage("_constraints/{0}", type), null), Map.class);
    }

    /**
     * Add a new constraint for a given field.
     *
     * @param type  a type
     * @param field a field name
     * @param c     the constraint
     * @return a map containing all validation constraints for this type.
     */
    public Map<String, Map<String, Map<String, Map<String, ?>>>> addValidationConstraint(String type,
                                                                                         String field, Constraint c) {
        if (StringUtils.isBlank(type) || StringUtils.isBlank(field) || c == null) {
            return Collections.emptyMap();
        }
        return getEntity(invokePut(Utils.formatMessage("_constraints/{0}/{1}/{2}", type,
                field, c.getName()), Entity.json(c.getPayload())), Map.class);
    }

    /**
     * Removes a validation constraint for a given field.
     *
     * @param type           a type
     * @param field          a field name
     * @param constraintName the name of the constraint to remove
     * @return a map containing all validation constraints for this type.
     */
    public Map<String, Map<String, Map<String, Map<String, ?>>>> removeValidationConstraint(String type,
                                                                                            String field, String constraintName) {
        if (StringUtils.isBlank(type) || StringUtils.isBlank(field) || StringUtils.isBlank(constraintName)) {
            return Collections.emptyMap();
        }
        return getEntity(invokeDelete(Utils.formatMessage("_constraints/{0}/{1}/{2}", type,
                field, constraintName), null), Map.class);
    }

    /////////////////////////////////////////////
    //			Resource Permissions
    /////////////////////////////////////////////

    /**
     * Returns the permissions for all subjects and resources for current app.
     *
     * @return a map of subject ids to resource names to a list of allowed methods
     */
    public Map<String, Map<String, List<String>>> resourcePermissions() {
        return getEntity(invokeGet("_permissions", null), Map.class);
    }

    /**
     * Returns only the permissions for a given subject (user) of the current app.
     *
     * @param subjectid the subject id (user id)
     * @return a map of subject ids to resource names to a list of allowed methods
     */
    public Map<String, Map<String, List<String>>> resourcePermissions(String subjectid) {
        return getEntity(invokeGet(Utils.formatMessage("_permissions/{0}", subjectid), null), Map.class);
    }

    /**
     * Grants a permission to a subject that allows them to call the specified HTTP methods on a given resource.
     *
     * @param subjectid    subject id (user id)
     * @param resourcePath resource path or object type
     * @param permission   a set of HTTP methods
     * @return a map of the permissions for this subject id
     */
    public Map<String, Map<String, List<String>>> grantResourcePermission(String subjectid, String resourcePath,
                                                                          EnumSet<App.AllowedMethods> permission) {
        return grantResourcePermission(subjectid, resourcePath, permission, false);
    }

    /**
     * Grants a permission to a subject that allows them to call the specified HTTP methods on a given resource.
     *
     * @param subjectid        subject id (user id)
     * @param resourcePath     resource path or object type
     * @param permission       a set of HTTP methods
     * @param allowGuestAccess if true - all unauthenticated requests will go through, 'false' by default.
     * @return a map of the permissions for this subject id
     */
    public Map<String, Map<String, List<String>>> grantResourcePermission(String subjectid, String resourcePath,
                                                                          EnumSet<App.AllowedMethods> permission, boolean allowGuestAccess) {
        if (StringUtils.isBlank(subjectid) || StringUtils.isBlank(resourcePath) || permission == null) {
            return Collections.emptyMap();
        }
        if (allowGuestAccess && App.ALLOW_ALL.equals(subjectid)) {
            permission.add(App.AllowedMethods.GUEST);
        }
        resourcePath = Utils.urlEncode(resourcePath);
        return getEntity(invokePut(Utils.formatMessage("_permissions/{0}/{1}", subjectid, resourcePath),
                Entity.json(permission)), Map.class);
    }

    /**
     * Revokes a permission for a subject, meaning they no longer will be able to access the given resource.
     *
     * @param subjectid    subject id (user id)
     * @param resourcePath resource path or object type
     * @return a map of the permissions for this subject id
     */
    public Map<String, Map<String, List<String>>> revokeResourcePermission(String subjectid, String resourcePath) {
        if (StringUtils.isBlank(subjectid) || StringUtils.isBlank(resourcePath)) {
            return Collections.emptyMap();
        }
        resourcePath = Utils.urlEncode(resourcePath);
        return getEntity(invokeDelete(Utils.formatMessage("_permissions/{0}/{1}", subjectid, resourcePath),
                null), Map.class);
    }

    /**
     * Revokes all permission for a subject.
     *
     * @param subjectid subject id (user id)
     * @return a map of the permissions for this subject id
     */
    public Map<String, Map<String, List<String>>> revokeAllResourcePermissions(String subjectid) {
        if (StringUtils.isBlank(subjectid)) {
            return Collections.emptyMap();
        }
        return getEntity(invokeDelete(Utils.formatMessage("_permissions/{0}", subjectid), null), Map.class);
    }

    /**
     * Checks if a subject is allowed to call method X on resource Y.
     *
     * @param subjectid    subject id
     * @param resourcePath resource path or object type
     * @param httpMethod   HTTP method name
     * @return true if allowed
     */
    public boolean isAllowedTo(String subjectid, String resourcePath, String httpMethod) {
        if (StringUtils.isBlank(subjectid) || StringUtils.isBlank(resourcePath) || StringUtils.isBlank(httpMethod)) {
            return false;
        }
        resourcePath = Utils.urlEncode(resourcePath);
        String url = Utils.formatMessage("_permissions/{0}/{1}/{2}", subjectid, resourcePath, httpMethod);
        Boolean result = getEntity(invokeGet(url, null), Boolean.class);
        return result != null && result;
    }

    /////////////////////////////////////////////
    //			App Settings
    /////////////////////////////////////////////

    /**
     * Returns the map containing app-specific settings.
     *
     * @return a map
     */
    public Map<String, Object> appSettings() {
        return getEntity(invokeGet("_settings", null), Map.class);
    }

    /**
     * Returns the value of a specific app setting (property).
     *
     * @param key a key
     * @return a map containing one element {"value": "the_value"} or an empty map.
     */
    public Map<String, Object> appSettings(String key) {
        if (StringUtils.isBlank(key)) {
            return appSettings();
        }
        return getEntity(invokeGet(Utils.formatMessage("_settings/{0}", key), null), Map.class);
    }

    /**
     * Adds or overwrites an app-specific setting.
     *
     * @param key   a key
     * @param value a value
     */
    public void addAppSetting(String key, Object value) {
        if (!StringUtils.isBlank(key) && value != null) {
            invokePut(Utils.formatMessage("_settings/{0}", key), Entity.json(Collections.singletonMap("value", value)));
        }
    }

    /**
     * Overwrites all app-specific settings.
     *
     * @param settings a key-value map of properties
     */
    public void setAppSettings(Map<?, ?> settings) {
        if (settings != null) {
            invokePut("_settings", Entity.json(settings));
        }
    }

    /**
     * Removes an app-specific setting.
     *
     * @param key a key
     */
    public void removeAppSetting(String key) {
        if (!StringUtils.isBlank(key)) {
            invokeDelete(Utils.formatMessage("_settings/{0}", key), null);
        }
    }

    /////////////////////////////////////////////
    //				Access Tokens
    /////////////////////////////////////////////

    /**
     * Takes an identity provider access token and fetches the user data from that provider.
     * A new {@link  User} object is created if that user doesn't exist.
     * Access tokens are returned upon successful authentication using one of the SDKs from
     * Facebook, Google, Twitter, etc.
     * <b>Note:</b> Twitter uses OAuth 1 and gives you a token and a token secret.
     * <b>You must concatenate them like this: <code>{oauth_token}:{oauth_token_secret}</code> and
     * use that as the provider access token.</b>
     *
     * @param provider      identity provider, e.g. 'facebook', 'google'...
     * @param providerToken access token from a provider like Facebook, Google, Twitter
     * @param rememberJWT   it true the access token returned by Para will be stored locally and
     *                      available through {@link #getAccessToken()}
     * @return a {@link User} object or null if something failed. The JWT is available
     * on the returned User object via {@link User#getPassword()}.
     */
    @SuppressWarnings("unchecked")
    public User signIn(String provider, String providerToken, boolean rememberJWT) {
        if (!StringUtils.isBlank(provider) && !StringUtils.isBlank(providerToken)) {
            Map<String, String> credentials = new HashMap<>();
            credentials.put(Config._APPID, accessKey);
            credentials.put("provider", provider);
            credentials.put("token", providerToken);
            Map<String, Object> result = getEntity(invokePost(JWT_PATH, Entity.json(credentials)), Map.class);
            if (result != null && result.containsKey("user") && result.containsKey("jwt")) {
                Map<?, ?> jwtData = (Map<?, ?>) result.get("jwt");
                if (rememberJWT) {
                    tokenKey = (String) jwtData.get("access_token");
                    tokenKeyExpires = (Long) jwtData.get("expires");
                    tokenKeyNextRefresh = (Long) jwtData.get("refresh");
                }
                User signedInUser = ParaObjectUtils.setAnnotatedFields((Map<String, Object>) result.get("user"));
                signedInUser.setPassword((String) jwtData.get("access_token"));
                return signedInUser;
            } else {
                clearAccessToken();
            }
        }
        return null;
    }

    /**
     * @param provider      identity provider, e.g. 'facebook', 'google'...
     * @param providerToken access token from a provider like Facebook, Google, Twitter
     * @return a {@link User} object or null if something failed
     * @see #signIn(java.lang.String, java.lang.String, boolean)
     */
    public User signIn(String provider, String providerToken) {
        return signIn(provider, providerToken, true);
    }

    /**
     * Clears the JWT access token but token is not revoked.
     * Tokens can be revoked globally per user with {@link #revokeAllTokens()}.
     */
    public void signOut() {
        clearAccessToken();
    }

    /**
     * Refreshes the JWT access token. This requires a valid existing token.
     * Call {@link #signIn(java.lang.String, java.lang.String)} first.
     *
     * @return true if token was refreshed
     */
    protected boolean refreshToken() {
        long now = System.currentTimeMillis();
        boolean notExpired = tokenKeyExpires != null && tokenKeyExpires > now;
        boolean canRefresh = tokenKeyNextRefresh != null &&
                (tokenKeyNextRefresh < now || tokenKeyNextRefresh > tokenKeyExpires);
        // token present and NOT expired
        if (tokenKey != null && notExpired && canRefresh) {
            Map<String, Object> result = getEntity(invokeGet(JWT_PATH, null), Map.class);
            if (result != null && result.containsKey("user") && result.containsKey("jwt")) {
                Map<?, ?> jwtData = (Map<?, ?>) result.get("jwt");
                tokenKey = (String) jwtData.get("access_token");
                tokenKeyExpires = (Long) jwtData.get("expires");
                tokenKeyNextRefresh = (Long) jwtData.get("refresh");
                return true;
            } else {
                clearAccessToken();
            }
        }
        return false;
    }

    /**
     * Revokes all user tokens for a given user id.
     * This would be equivalent to "logout everywhere".
     * <b>Note:</b> Generating a new API secret on the server will also invalidate all client tokens.
     * Requires a valid existing token.
     *
     * @return true if successful
     */
    public boolean revokeAllTokens() {
        return getEntity(invokeDelete(JWT_PATH, null), Map.class) != null;
    }

}
