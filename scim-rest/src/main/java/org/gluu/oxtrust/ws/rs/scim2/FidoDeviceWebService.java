package org.gluu.oxtrust.ws.rs.scim2;

import static org.gluu.oxtrust.model.scim2.Constants.MEDIA_TYPE_SCIM_JSON;
import static org.gluu.oxtrust.model.scim2.Constants.QUERY_PARAM_ATTRIBUTES;
import static org.gluu.oxtrust.model.scim2.Constants.QUERY_PARAM_COUNT;
import static org.gluu.oxtrust.model.scim2.Constants.QUERY_PARAM_EXCLUDED_ATTRS;
import static org.gluu.oxtrust.model.scim2.Constants.QUERY_PARAM_FILTER;
import static org.gluu.oxtrust.model.scim2.Constants.QUERY_PARAM_SORT_BY;
import static org.gluu.oxtrust.model.scim2.Constants.QUERY_PARAM_SORT_ORDER;
import static org.gluu.oxtrust.model.scim2.Constants.QUERY_PARAM_START_INDEX;
import static org.gluu.oxtrust.model.scim2.Constants.UTF8_CHARSET_FRAGMENT;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.management.InvalidAttributeValueException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.lang.StringUtils;

import org.gluu.oxtrust.model.exception.SCIMException;
import org.gluu.oxtrust.model.fido.GluuCustomFidoDevice;
import org.gluu.oxtrust.model.scim2.BaseScimResource;
import org.gluu.oxtrust.model.scim2.ErrorScimType;
import org.gluu.oxtrust.model.scim2.Meta;
import org.gluu.oxtrust.model.scim2.SearchRequest;
import org.gluu.oxtrust.model.scim2.fido.FidoDeviceResource;
import org.gluu.oxtrust.model.scim2.patch.PatchRequest;
import org.gluu.oxtrust.model.scim2.util.DateUtil;
import org.gluu.oxtrust.model.scim2.util.ScimResourceUtil;
import org.gluu.oxtrust.service.IFidoDeviceService;
import org.gluu.oxtrust.service.antlr.scimFilter.ScimFilterParserService;
import org.gluu.oxtrust.service.filter.ProtectedApi;
import org.gluu.oxtrust.service.scim2.interceptor.RefAdjusted;
import org.gluu.persist.PersistenceEntryManager;
import org.gluu.persist.model.PagedResult;
import org.gluu.persist.model.SortOrder;
import org.gluu.search.filter.Filter;

/**
 * Implementation of /FidoDevices endpoint. Methods here are intercepted.
 * Filter org.gluu.oxtrust.ws.rs.scim2.AuthorizationProcessingFilter secures invocations
 */
@Named("scim2FidoDeviceEndpoint")
@Path("/scim/v2/FidoDevices")
public class FidoDeviceWebService extends BaseScimWebService implements IFidoDeviceWebService {
    
    @Inject
	private IFidoDeviceService fidoDeviceService;

    @Inject
    private ScimFilterParserService scimFilterParserService;

    @Inject
    private PersistenceEntryManager entryManager;

    private boolean ldapBackend;
    
    private String fidoResourceType;
    
    private Response doSearchDevices(String userId, String filter, Integer startIndex, 
            Integer count, String sortBy, String sortOrder, String attrsList, String excludedAttrsList,
            String method) {

        Response response;
        try {
            SearchRequest searchReq = new SearchRequest();
            response = prepareSearchRequest(searchReq.getSchemas(), filter, sortBy,
                    sortOrder, startIndex, count, attrsList, excludedAttrsList, searchReq);
            if (response != null) return response;
            
            response = externalConstraintsService.applySearchCheck(searchReq,
                    httpHeaders, uriInfo, method, fidoResourceType);
            if (response != null) return response;

            response = validateExistenceOfUser(userId);
            if (response != null) return response;

            PagedResult<BaseScimResource> resources = searchDevices(userId, searchReq.getFilter(), 
                    translateSortByAttribute(FidoDeviceResource.class, searchReq.getSortBy()), 
                    SortOrder.getByValue(searchReq.getSortOrder()), searchReq.getStartIndex(),
                    searchReq.getCount());

            String json = getListResponseSerialized(resources.getTotalEntriesCount(), 
                    searchReq.getStartIndex(), resources.getEntries(), searchReq.getAttributesStr(), 
                    searchReq.getExcludedAttributesStr(), searchReq.getCount() == 0);
            response = Response.ok(json).location(new URI(endpointUrl)).build();
        } catch (SCIMException e) {
            log.error(e.getMessage(), e);
            response = getErrorResponse(Response.Status.BAD_REQUEST, ErrorScimType.INVALID_FILTER,
                    e.getMessage());
        } catch (Exception e) {
            log.error("Failure at searchDevices method", e);
            response = getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unexpected error: " + e.getMessage());
        }
        return response;
        
    }
    
    @POST
    @Consumes({MEDIA_TYPE_SCIM_JSON, MediaType.APPLICATION_JSON})
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ProtectedApi(oauthScopes = { "https://gluu.org/scim/fido.write" })
    public Response createDevice() {
        log.debug("Executing web service method. createDevice");
        return getErrorResponse(Response.Status.NOT_IMPLEMENTED, "Not implemented; device registration only happens via the FIDO API.");
    }

    @Path("{id}")
    @GET
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ProtectedApi(oauthScopes = { "https://gluu.org/scim/fido.read" })
    @RefAdjusted
    public Response getDeviceById(@PathParam("id") String id,
                           @QueryParam("userId") String userId,
                           @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
                           @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList) {

        Response response;
        try {
            log.debug("Executing web service method. getDeviceById");

            GluuCustomFidoDevice device = fidoDeviceService.getGluuCustomFidoDeviceById(userId, id);
            if (device == null) return notFoundResponse(id, fidoResourceType);
            
            response = externalConstraintsService.applyEntityCheck(device, null,
                    httpHeaders, uriInfo, HttpMethod.GET, fidoResourceType);
            if (response != null) return response;

            FidoDeviceResource fidoResource = new FidoDeviceResource();
            transferAttributesToFidoResource(device, fidoResource, endpointUrl,
                userPersistenceHelper.getUserInumFromDN(device.getDn()));

            String json = resourceSerializer.serialize(fidoResource, attrsList, excludedAttrsList);
            response = Response.ok(new URI(fidoResource.getMeta().getLocation())).entity(json).build();
        } catch (Exception e) {
            log.error("Failure at getDeviceById method", e);
            response = getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    @Path("{id}")
    @PUT
    @Consumes({MEDIA_TYPE_SCIM_JSON, MediaType.APPLICATION_JSON})
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ProtectedApi(oauthScopes = { "https://gluu.org/scim/fido.write" })
    @RefAdjusted
    public Response updateDevice(
            FidoDeviceResource fidoDeviceResource,
            @PathParam("id") String id,
            @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
            @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList) {

        Response response;
        try {
            log.debug("Executing web service method. updateDevice");

            //remove externalId, no place to store it in LDAP
            fidoDeviceResource.setExternalId(null);

            if (fidoDeviceResource.getId() != null && !fidoDeviceResource.getId().equals(id))
                throw new SCIMException("Parameter id does not match id attribute of Device");

            String userId = fidoDeviceResource.getUserId();
            GluuCustomFidoDevice device = fidoDeviceService.getGluuCustomFidoDeviceById(userId, id);            
            if (device == null) return notFoundResponse(id, fidoResourceType);

            response = externalConstraintsService.applyEntityCheck(device, fidoDeviceResource,
                    httpHeaders, uriInfo, HttpMethod.PUT, fidoResourceType);
            if (response != null) return response;
            
            executeValidation(fidoDeviceResource, true);
            
            FidoDeviceResource updatedResource = new FidoDeviceResource();
            transferAttributesToFidoResource(device, updatedResource, endpointUrl, userId);

            updatedResource.getMeta().setLastModified(DateUtil.millisToISOString(System.currentTimeMillis()));

            updatedResource = (FidoDeviceResource) ScimResourceUtil.transferToResourceReplace(fidoDeviceResource,
                    updatedResource, extService.getResourceExtensions(updatedResource.getClass()));
            transferAttributesToDevice(updatedResource, device);

            fidoDeviceService.updateGluuCustomFidoDevice(device);

            String json = resourceSerializer.serialize(updatedResource, attrsList, excludedAttrsList);
            response = Response.ok(new URI(updatedResource.getMeta().getLocation())).entity(json).build();
        } catch (SCIMException e) {
            log.error("Validation check error: {}", e.getMessage());
            response = getErrorResponse(Response.Status.BAD_REQUEST, ErrorScimType.INVALID_VALUE, e.getMessage());
        } catch (InvalidAttributeValueException e) {
            log.error(e.getMessage());
            response = getErrorResponse(Response.Status.BAD_REQUEST, ErrorScimType.MUTABILITY, e.getMessage());
        } catch (Exception e) {
            log.error("Failure at updateDevice method", e);
            response = getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    @Path("{id}")
    @DELETE
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ProtectedApi(oauthScopes = { "https://gluu.org/scim/fido.write" })
    public Response deleteDevice(@PathParam("id") String id) {

        Response response;
        try {
            log.debug("Executing web service method. deleteDevice");

            GluuCustomFidoDevice device = fidoDeviceService.getGluuCustomFidoDeviceById(null, id);
            if (device == null) return notFoundResponse(id, fidoResourceType);

            response = externalConstraintsService.applyEntityCheck(device, null,
                    httpHeaders, uriInfo, HttpMethod.DELETE, fidoResourceType);
            if (response != null) return response;

            fidoDeviceService.removeGluuCustomFidoDevice(device);
            response = Response.noContent().build();
        } catch (Exception e) {
            log.error("Failure at deleteDevice method", e);
            response = getErrorResponse(Response.Status.INTERNAL_SERVER_ERROR, 
                    "Unexpected error: " + e.getMessage());
        }
        return response;

    }

    @GET
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ProtectedApi(oauthScopes = { "https://gluu.org/scim/fido.read" })
    @RefAdjusted
    public Response searchDevices(
            @QueryParam("userId") String userId,
            @QueryParam(QUERY_PARAM_FILTER) String filter,
            @QueryParam(QUERY_PARAM_START_INDEX) Integer startIndex,
            @QueryParam(QUERY_PARAM_COUNT) Integer count,
            @QueryParam(QUERY_PARAM_SORT_BY) String sortBy,
            @QueryParam(QUERY_PARAM_SORT_ORDER) String sortOrder,
            @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
            @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList) {

        log.debug("Executing web service method. searchDevices");
        return doSearchDevices(userId, filter, startIndex, count, sortBy, sortOrder,
                attrsList, excludedAttrsList, HttpMethod.GET);

    }

    @Path(SEARCH_SUFFIX)
    @POST
    @Consumes({MEDIA_TYPE_SCIM_JSON, MediaType.APPLICATION_JSON})
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ProtectedApi(oauthScopes = { "https://gluu.org/scim/fido.read" })
    @RefAdjusted
    public Response searchDevicesPost(SearchRequest searchRequest, @QueryParam("userId") String userId) {

        log.debug("Executing web service method. searchDevicesPost");
        Response response = doSearchDevices(userId, searchRequest.getFilter(), searchRequest.getStartIndex(), 
                searchRequest.getCount(), searchRequest.getSortBy(), searchRequest.getSortOrder(), 
                searchRequest.getAttributesStr(), searchRequest.getExcludedAttributesStr(),
                HttpMethod.POST);

        URI uri = null;
        try {
            uri = new URI(endpointUrl + "/" + SEARCH_SUFFIX);
        } catch (URISyntaxException e) {
            log.error(e.getMessage(), e);
        }
        return Response.fromResponse(response).location(uri).build();

    }

    private void transferAttributesToFidoResource(GluuCustomFidoDevice fidoDevice, FidoDeviceResource res, String url, String userId) {

        res.setId(fidoDevice.getId());

        Meta meta=new Meta();
        meta.setResourceType(ScimResourceUtil.getType(res.getClass()));

        String strDate = fidoDevice.getCreationDate();
        meta.setCreated(ldapBackend ? DateUtil.generalizedToISOStringDate(strDate) : (strDate + "Z"));
        meta.setLastModified(fidoDevice.getMetaLastModified());
        meta.setLocation(fidoDevice.getMetaLocation());
        if (meta.getLocation()==null)
            meta.setLocation(url + "/" + fidoDevice.getId());

        res.setMeta(meta);

        //Set values in order of appearance in FidoDeviceResource class
        res.setUserId(userId);
        res.setCreationDate(meta.getCreated());
        res.setApplication(fidoDevice.getApplication());
        res.setCounter(fidoDevice.getCounter());

        res.setDeviceData(fidoDevice.getDeviceData());
        res.setDeviceHashCode(fidoDevice.getDeviceHashCode());
        res.setDeviceKeyHandle(fidoDevice.getDeviceKeyHandle());
        res.setDeviceRegistrationConf(fidoDevice.getDeviceRegistrationConf());

        strDate = fidoDevice.getLastAccessTime();
        if (strDate != null) {
            res.setLastAccessTime(ldapBackend ? DateUtil.generalizedToISOStringDate(strDate) : (strDate + "Z"));
        }
        res.setStatus(fidoDevice.getStatus());
        res.setDisplayName(fidoDevice.getDisplayName());
        res.setDescription(fidoDevice.getDescription());
        res.setNickname(fidoDevice.getNickname());

    }

    /**
     * In practice, transference of values will not necessarily modify all original values in LDAP...
     * @param res
     * @param device
     */
    private void transferAttributesToDevice(FidoDeviceResource res, GluuCustomFidoDevice device){

        //Set values trying to follow the order found in GluuCustomFidoDevice class
        device.setId(res.getId());
        String strDate = res.getMeta().getCreated();
        device.setCreationDate(ldapBackend ? DateUtil.ISOToGeneralizedStringDate(strDate) : DateUtil.gluuCouchbaseISODate(strDate));
        device.setApplication(res.getApplication());
        device.setCounter(res.getCounter());

        device.setDeviceData(res.getDeviceData());
        device.setDeviceHashCode(res.getDeviceHashCode());
        device.setDeviceKeyHandle(res.getDeviceKeyHandle());
        device.setDeviceRegistrationConf(res.getDeviceRegistrationConf());

        strDate = res.getLastAccessTime();
        device.setLastAccessTime(ldapBackend ? DateUtil.ISOToGeneralizedStringDate(strDate) : DateUtil.gluuCouchbaseISODate(strDate));
        device.setStatus(res.getStatus());
        device.setDisplayName(res.getDisplayName());
        device.setDescription(res.getDescription());
        device.setNickname(res.getNickname());

        device.setMetaLastModified(res.getMeta().getLastModified());
        device.setMetaLocation(res.getMeta().getLocation());
        device.setMetaVersion(res.getMeta().getVersion());

    }

    private PagedResult<BaseScimResource> searchDevices(String userId, String filter, String sortBy, SortOrder sortOrder, int startIndex,
                                                    int count) throws Exception {

        Filter ldapFilter=scimFilterParserService.createFilter(filter, Filter.createPresenceFilter("oxId"), FidoDeviceResource.class);
        log.info("Executing search for fido devices using: ldapfilter '{}', sortBy '{}', sortOrder '{}', startIndex '{}', count '{}', userId '{}'",
                ldapFilter.toString(), sortBy, sortOrder.getValue(), startIndex, count, userId);

        //workaround for https://github.com/GluuFederation/scim/issues/1: 
        //Currently, searching with SUB scope in Couchbase requires some help (beyond use of baseDN) 
        if (StringUtils.isNotEmpty(userId)) {
        	ldapFilter=Filter.createANDFilter(ldapFilter, Filter.createEqualityFilter("personInum", userId));
        }
        
        PagedResult<GluuCustomFidoDevice> list;
        try {
            list = entryManager.findPagedEntries(fidoDeviceService.getDnForFidoDevice(userId, null),
                    GluuCustomFidoDevice.class, ldapFilter, null, sortBy, sortOrder, startIndex - 1, count, getMaxCount());
        } catch (Exception e) {
            log.info("Returning an empty listViewReponse");
            log.error(e.getMessage(), e);
            list = new PagedResult<>();
            list.setEntries(new ArrayList<>());
        }
        List<BaseScimResource> resources=new ArrayList<>();

        for (GluuCustomFidoDevice device : list.getEntries()){
            FidoDeviceResource scimDev=new FidoDeviceResource();
            transferAttributesToFidoResource(device, scimDev, endpointUrl,
                userPersistenceHelper.getUserInumFromDN(device.getDn()));
            resources.add(scimDev);
        }
        log.info ("Found {} matching entries - returning {}", list.getTotalEntriesCount(), list.getEntries().size());

        PagedResult<BaseScimResource> result = new PagedResult<>();
        result.setEntries(resources);
        result.setTotalEntriesCount(list.getTotalEntriesCount());
        
        return result;

    }

    @Path("{id}")
    @PATCH
    @Consumes({MEDIA_TYPE_SCIM_JSON, MediaType.APPLICATION_JSON})
    @Produces({MEDIA_TYPE_SCIM_JSON + UTF8_CHARSET_FRAGMENT, MediaType.APPLICATION_JSON + UTF8_CHARSET_FRAGMENT})
    @HeaderParam("Accept") @DefaultValue(MEDIA_TYPE_SCIM_JSON)
    @ProtectedApi(oauthScopes = { "https://gluu.org/scim/fido.write" })
    @RefAdjusted
    public Response patchDevice(
            PatchRequest request,
            @PathParam("id") String id,
            @QueryParam(QUERY_PARAM_ATTRIBUTES) String attrsList,
            @QueryParam(QUERY_PARAM_EXCLUDED_ATTRS) String excludedAttrsList){

        log.debug("Executing web service method. patchDevice");
        return getErrorResponse(Response.Status.NOT_IMPLEMENTED, "Patch operation not supported for FIDO devices");
    }

    @PostConstruct
    public void setup(){
        //Do not use getClass() here...
        init(FidoDeviceWebService.class);
        ldapBackend = scimFilterParserService.isLdapBackend();
        fidoResourceType = ScimResourceUtil.getType(FidoDeviceResource.class);
    }

}
