package manage.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import manage.api.APIUser;
import manage.conf.MetaDataAutoConfiguration;
import manage.exception.DuplicateEntityIdException;
import manage.exception.EndpointNotAllowed;
import manage.exception.ResourceNotFoundException;
import manage.format.Exporter;
import manage.format.Importer;
import manage.format.SaveURLResource;
import manage.hook.MetaDataHook;
import manage.model.DashboardConnectOption;
import manage.model.EntityType;
import manage.model.Import;
import manage.model.MetaData;
import manage.model.MetaDataKeyDelete;
import manage.model.MetaDataUpdate;
import manage.model.RevisionRestore;
import manage.model.Scope;
import manage.model.ServiceProvider;
import manage.model.StatsEntry;
import manage.model.XML;
import manage.oidc.Client;
import manage.oidc.OpenIdConnect;
import manage.repository.MetaDataRepository;
import manage.shibboleth.FederatedUser;
import org.everit.json.schema.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static manage.api.Scope.TEST;
import static manage.hook.OpenIdConnectHook.OIDC_CLIENT_KEY;
import static manage.hook.OpenIdConnectHook.translateServiceProviderEntityId;
import static manage.mongo.MongoChangelog.REVISION_POSTFIX;

@RestController
@SuppressWarnings("unchecked")
public class MetaDataController {

    static final String REQUESTED_ATTRIBUTES = "REQUESTED_ATTRIBUTES";
    static final String ALL_ATTRIBUTES = "ALL_ATTRIBUTES";
    static final String LOGICAL_OPERATOR_IS_AND = "LOGICAL_OPERATOR_IS_AND";

    private static final String DASHBOARD_CONNECT_OPTION = "coin:dashboard_connect_option";

    private static final Logger LOG = LoggerFactory.getLogger(MetaDataController.class);

    private static final List<String> entityTypesSuggestions = Arrays.asList(
            EntityType.RP.getType(), EntityType.SP.getType()
    );

    private MetaDataRepository metaDataRepository;
    private MetaDataAutoConfiguration metaDataAutoConfiguration;
    private MetaDataHook metaDataHook;
    private Importer importer;
    private Exporter exporter;
    private OpenIdConnect openIdConnect;
    private String baseDomain;
    private Environment environment;

    @Autowired
    DatabaseController databaseController;

    @Autowired
    public MetaDataController(MetaDataRepository metaDataRepository,
                              MetaDataAutoConfiguration metaDataAutoConfiguration,
                              ResourceLoader resourceLoader,
                              MetaDataHook metaDataHook,
                              OpenIdConnect openIdConnect,
                              Environment environment,
                              @Value("${metadata_export_path}") String metadataExportPath,
                              @Value("${base_domain}") String baseDomain,
                              @Value("${product.supported_languages}") String supportedLanguages) {
        this.metaDataRepository = metaDataRepository;
        this.metaDataAutoConfiguration = metaDataAutoConfiguration;
        this.metaDataHook = metaDataHook;
        List<String> languages = Stream.of(supportedLanguages.split(",")).map(String::trim).collect(toList());

        this.importer = new Importer(metaDataAutoConfiguration, languages);
        this.exporter = new Exporter(Clock.systemDefaultZone(), resourceLoader, metadataExportPath, languages);
        this.openIdConnect = openIdConnect;
        this.baseDomain = baseDomain;
        this.environment = environment;

    }

    @GetMapping("/client/template/{type}")
    public MetaData template(@PathVariable("type") String type) {
        Map<String, Object> data = metaDataAutoConfiguration.metaDataTemplate(type);
        return new MetaData(type, data);
    }

    @GetMapping({"/client/metadata/{type}/{id}", "/internal/metadata/{type}/{id}"})
    public MetaData get(@PathVariable("type") String type, @PathVariable("id") String id) {
        MetaData metaData = metaDataRepository.findById(id, type);
        checkNull(type, id, metaData);
        return metaDataHook.postGet(metaData);
    }

    private void checkNull(String type, String id, MetaData metaData) {
        if (metaData == null) {
            throw new ResourceNotFoundException(String.format("MetaData type %s with id %s does not exist", type, id));
        }
    }

    @GetMapping("/client/metadata/configuration")
    public List<Map<String, Object>> configuration() {
        return metaDataAutoConfiguration.schemaRepresentations();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/client/metadata")
    public MetaData post(@Validated @RequestBody MetaData metaData, FederatedUser federatedUser) throws
            JsonProcessingException {
        return doPost(metaData, federatedUser.getUid(), false);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/client/includeInPush/{type}/{id}")
    public MetaData includeInPush(@PathVariable("type") String type, @PathVariable("id") String id,
                                  FederatedUser federatedUser) throws JsonProcessingException {
        MetaData metaData = this.get(type, id);
        Map metaDataFields = metaData.metaDataFields();
        metaDataFields.remove("coin:exclude_from_push");
        //Bugfix for enriched metadata from the get
        metaData.getData().remove(OIDC_CLIENT_KEY);

        return doPut(metaData, federatedUser.getUid(), false);
    }

    @GetMapping("/client/metadata/stats")
    public List<StatsEntry> stats() {
        return metaDataRepository.stats();
    }

    @PreAuthorize("hasRole('WRITE')")
    @PostMapping("/internal/metadata")
    public MetaData postInternal(@Validated @RequestBody MetaData metaData, APIUser apiUser) throws
            JsonProcessingException {
        return doPost(metaData, apiUser.getName(), !apiUser.getScopes().contains(TEST));
    }

    @PreAuthorize("hasRole('WRITE')")
    @PostMapping("/internal/new-sp")
    public MetaData newSP(@Validated @RequestBody XML container, APIUser apiUser) throws
            IOException, XMLStreamException {
        Map<String, Object> innerJson = this.importer.importXML(new ByteArrayResource(container.getXml()
                .getBytes()), EntityType.SP, Optional.empty());

        addDefaultSpData(innerJson);
        MetaData metaData = new MetaData(EntityType.SP.getType(), innerJson);

        return doPost(metaData, apiUser.getName(), !apiUser.getScopes().contains(TEST));
    }


    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping(value = "/client/delete/feed")
    public Map<String, Long> deleteFeed() {
        long deleted = this.metaDataRepository.deleteAllImportedServiceProviders();
        return Collections.singletonMap("deleted", deleted);
    }

    @GetMapping(value = "/client/count/feed")
    public Map<String, Long> countFeed() {
        long count = this.metaDataRepository.countAllImportedServiceProviders();
        return Collections.singletonMap("count", count);
    }


    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/client/import/feed")
    public Map<String, List> importFeed(@Validated @RequestBody Import importRequest) {
        try {
            Map<String, ServiceProvider> serviceProviderMap =
                    metaDataRepository.allServiceProviderEntityIds().stream()
                            .map(ServiceProvider::new)
                            .collect(Collectors.toMap(sp -> sp.getEntityId(), sp -> sp));
            String feedUrl = importRequest.getUrl();
            Resource resource = new SaveURLResource(new URL(feedUrl), environment.acceptsProfiles(Profiles.of("dev")));

            List<Map<String, Object>> allImports = this.importer.importFeed(resource);
            List<Map<String, Object>> imports =
                    allImports.stream().filter(m -> !m.isEmpty()).collect(toList());

            Map<String, List> results = new HashMap<>();
            EntityType entityType = EntityType.SP;
            imports.forEach(sp -> {
                String entityId = (String) sp.get("entityid");
                sp.put("metadataurl", feedUrl);
                Map metaDataFields = Map.class.cast(sp.get("metaDataFields"));
                metaDataFields.put("coin:imported_from_edugain", true);
                metaDataFields.put("coin:interfed_source", "eduGAIN");

                ServiceProvider existingServiceProvider = serviceProviderMap.get(entityId);
                if (existingServiceProvider != null) {
                    if (existingServiceProvider.isPublishedInEduGain()) {
                        // Do not import this SP as it's source is SURFconext
                        List publishedInEdugain = results.computeIfAbsent("published_in_edugain", s -> new ArrayList());
                        publishedInEdugain.add(existingServiceProvider);
                    } else if (existingServiceProvider.isImportedFromEduGain()) {
                        try {
                            MetaDataUpdate metaDataUpdate =
                                    this.importToMetaDataUpdate(existingServiceProvider.getId(), entityType, sp, feedUrl);
                            Optional<MetaData> metaData = this.doMergeUpdate(metaDataUpdate, "edugain-import", "edugain-import", false);
                            if (metaData.isPresent()) {
                                List merged = results.computeIfAbsent("merged", s -> new ArrayList());
                                merged.add(existingServiceProvider);
                            } else {
                                List noChanges = results.computeIfAbsent("no_changes", s -> new ArrayList());
                                noChanges.add(existingServiceProvider);
                            }
                        } catch (JsonProcessingException | ValidationException e) {
                            addNoValid(results, entityId, e);
                        }
                    } else {
                        // Do not import this SP as it is modified after the import or is not imported at all
                        List notImported = results.computeIfAbsent("not_imported", s -> new ArrayList());
                        notImported.add(existingServiceProvider);
                    }
                } else {
                    try {
                        MetaData metaData = this.importToMetaData(sp, entityType);
                        MetaData persistedMetaData = this.doPost(metaData, "edugain-import", false);
                        List imported = results.computeIfAbsent("imported", s -> new ArrayList());
                        imported.add(new ServiceProvider(persistedMetaData.getId(), entityId, false, false, null));
                    } catch (JsonProcessingException | ValidationException e) {
                        addNoValid(results, entityId, e);
                    }
                }
            });
            List<ServiceProvider> notInFeedAnymore = serviceProviderMap.values().stream()
                    .filter(sp -> sp.isImportedFromEduGain() &&
                            !imports.stream().anyMatch(map -> sp.getEntityId().equals(map.get("entityid"))))
                    .collect(toList());
            notInFeedAnymore.forEach(sp -> this.doRemove(entityType.getType(), sp.getId(), "edugain-import", "Removed from eduGain feed"));

            List deleted = results.computeIfAbsent("deleted", s -> new ArrayList());
            deleted.addAll(notInFeedAnymore.stream().map(sp -> sp.getEntityId()).collect(toList()));

            results.put("total", Collections.singletonList(imports.size()));

            return results;
        } catch (IOException | XMLStreamException e) {
            return singletonMap("errors", singletonList(e.getClass().getName()));
        }
    }

    private void addNoValid(Map<String, List> results, String entityId, Exception e) {
        String msg = e instanceof ValidationException ?
                String.join(", ", ValidationException.class.cast(e).getAllMessages()) : e.getClass().getName();
        List notValid = results.computeIfAbsent("not_valid", s -> new ArrayList());
        Map<String, String> result = new HashMap<>();
        result.put("validationException", msg);
        result.put("entityId", entityId);
        notValid.add(result);
    }

    private MetaData importToMetaData(Map<String, Object> m, EntityType entityType) {
        MetaData template = this.template(entityType.getType());
        template.getData().putAll(m);
        template.getData().put("state", "prodaccepted");
        return template;
    }

    private MetaDataUpdate importToMetaDataUpdate(String id, EntityType entityType, Map<String, Object> m,
                                                  String feedUrl) {
        Map<String, Object> metaDataFields = Map.class.cast(m.get("metaDataFields"));
        Map<String, Object> pathUpdates = new HashMap<>();
        metaDataFields.forEach((k, v) -> pathUpdates.put("metaDataFields.".concat(k), v));
        pathUpdates.put("metadataurl", feedUrl);
        MetaDataUpdate metaDataUpdate = new MetaDataUpdate(id, entityType.getType(), pathUpdates, Collections.emptyMap());
        return metaDataUpdate;
    }

    private void addDefaultSpData(Map<String, Object> innerJson) {
        innerJson.put("allowedall", true);
        innerJson.put("state", "testaccepted");
        innerJson.put("allowedEntities", new ArrayList<>());
    }

    @PreAuthorize("hasRole('WRITE')")
    @PostMapping("/internal/update-sp/{id}/{version}")
    public MetaData updateSP(@PathVariable("id") String id,
                             @PathVariable("version") Long version,
                             @Validated @RequestBody XML container,
                             APIUser apiUser) throws IOException, XMLStreamException {
        MetaData metaData = this.get(EntityType.SP.getType(), id);
        Map<String, Object> innerJson = this.importer.importXML(new ByteArrayResource(container.getXml()
                .getBytes()), EntityType.SP, Optional.empty());

        addDefaultSpData(innerJson);

        metaData.setData(innerJson);
        metaData.setVersion(version);

        return doPut(metaData, apiUser.getName(), !apiUser.getScopes().contains(TEST));
    }

    @GetMapping("/internal/sp-metadata/{id}")
    public String exportXml(@PathVariable("id") String id) throws IOException {
        MetaData metaData = this.get(EntityType.SP.getType(), id);
        return exporter.exportToXml(metaData);
    }

    @GetMapping(value = "/internal/xml/metadata/{type}/{id}", produces = "text/xml")
    public String exportMetadataXml(@PathVariable("type") String type, @PathVariable("id") String id) throws IOException {
        MetaData metaData = this.get(EntityType.fromType(type).getType(), id);
        return exporter.exportToXml(metaData);
    }

    private MetaData doPost(@Validated @RequestBody MetaData metaData, String uid, boolean excludeFromPushRequired) throws JsonProcessingException {
        String entityid = (String) metaData.getData().get("entityid");
        List<Map> result = this.uniqueEntityId(metaData.getType(), singletonMap("entityid", entityid));
        if (!CollectionUtils.isEmpty(result)) {
            throw new DuplicateEntityIdException(entityid);
        }

        sanitizeExcludeFromPush(metaData, excludeFromPushRequired);
        metaData = metaDataHook.prePost(metaData);

        metaData = validate(metaData);
        Long eid = metaDataRepository.incrementEid();
        metaData.initial(UUID.randomUUID().toString(), uid, eid);

        LOG.info("Saving new metaData {} by {}", metaData.getId(), uid);

        metaDataRepository.save(metaData);

        return this.get(metaData.getType(), metaData.getId());
    }

    private void sanitizeExcludeFromPush(@RequestBody @Validated MetaData metaData, boolean excludeFromPushRequired) {
        Map metaDataFields = metaData.metaDataFields();
        Object val = metaDataFields.get("coin:exclude_from_push");
        if (excludeFromPushRequired && ("0".equals(val) || Boolean.FALSE == val)) {
            metaDataFields.put("coin:exclude_from_push", true);
        }
    }

    @PreAuthorize("hasRole('READ')")
    @PostMapping("/internal/validate/metadata")
    public ResponseEntity<Object> validateMetaData(@Validated @RequestBody MetaData metaData) throws
            JsonProcessingException {
        validate(metaData);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/client/metadata/{type}/{id}")
    public boolean remove(@PathVariable("type") String type, @PathVariable("id") String id, @RequestBody(required = false) Map body, FederatedUser user) {
        String defaultValue = "Deleted by " + user.getUid();
        String revisionNote = body != null ? (String) body.getOrDefault("revisionNote", defaultValue) : defaultValue;
        return doRemove(type, id, user.getUid(), revisionNote);
    }

    @PreAuthorize("hasRole('WRITE')")
    @DeleteMapping("/internal/metadata/{type}/{id}")
    public boolean removeInternal(@PathVariable("type") String type, @PathVariable("id") String id, APIUser apiUser) {
        return doRemove(type, id, apiUser.getName(), "Deleted by APIUser " + apiUser.getName());
    }

    private boolean doRemove(@PathVariable("type") String type, @PathVariable("id") String id, String uid, String revisionNote) {
        MetaData current = metaDataRepository.findById(id, type);
        checkNull(type, id, current);
        current = metaDataHook.preDelete(current);
        metaDataRepository.remove(current);

        LOG.info("Deleted metaData {} by {}", current.getId(), uid);

        current.revision(UUID.randomUUID().toString());
        metaDataRepository.save(current);

        current.terminate(UUID.randomUUID().toString(), revisionNote);
        metaDataRepository.save(current);
        return true;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/client/metadata")
    @Transactional
    public MetaData put(@Validated @RequestBody MetaData metaData, Authentication authentication) throws
            JsonProcessingException {
        return doPut(metaData, authentication.getName(), false);
    }

    @PreAuthorize("hasRole('WRITE')")
    @PutMapping("/internal/metadata")
    @Transactional
    public MetaData putInternal(@Validated @RequestBody MetaData metaData, APIUser apiUser) throws
            JsonProcessingException {
        return doPut(metaData, apiUser.getName(), !apiUser.getScopes().contains(TEST));
    }

    private MetaData doPut(@Validated @RequestBody MetaData metaData, String updatedBy, boolean excludeFromPushRequired) throws JsonProcessingException {
        String entityid = (String) metaData.getData().get("entityid");
        List<Map> result = this.uniqueEntityId(metaData.getType(), singletonMap("entityid", entityid));
        if (result.size() > 1) {
            throw new DuplicateEntityIdException(entityid);
        }

        sanitizeExcludeFromPush(metaData, excludeFromPushRequired);
        String id = metaData.getId();
        MetaData previous = metaDataRepository.findById(id, metaData.getType());
        checkNull(metaData.getType(), id, previous);

        metaData = metaDataHook.prePut(previous, metaData);
        metaData = validate(metaData);

        previous.revision(UUID.randomUUID().toString());
        metaDataRepository.save(previous);

        metaData.promoteToLatest(updatedBy, (String) metaData.getData().get("revisionnote"));
        metaDataRepository.update(metaData);

        LOG.info("Updated metaData {} by {}", metaData.getId(), updatedBy);

        return this.get(metaData.getType(), metaData.getId());
    }

    @PreAuthorize("hasRole('WRITE')")
    @PutMapping("/internal/delete-metadata-key")
    @Transactional
    public List<String> deleteMetaDataKey(@Validated @RequestBody MetaDataKeyDelete metaDataKeyDelete, APIUser apiUser) throws
            IOException {

        String keyToDelete = metaDataKeyDelete.getMetaDataKey();
        Query query = Query.query(Criteria.where("data.metaDataFields." + keyToDelete).exists(true));
        List<MetaData> metaDataList = metaDataRepository.getMongoTemplate().find(query, MetaData.class, metaDataKeyDelete.getType());

        //of we stream then we need to catch all exceptions including validation exception
        for (MetaData metaData : metaDataList) {
            metaData.metaDataFields().remove(keyToDelete);
            metaData = validate(metaData);

            MetaData previous = metaDataRepository.findById(metaData.getId(), metaData.getType());
            previous.revision(UUID.randomUUID().toString());
            metaDataRepository.save(previous);

            metaData.promoteToLatest(apiUser.getName(), String.format("API call for deleting %s by %s", keyToDelete, apiUser.getName()));
            metaDataRepository.update(metaData);
        }

        return metaDataList.stream().map(metaData -> (String) metaData.getData().get("entityid")).collect(toList());
    }


    @PreAuthorize("hasRole('WRITE')")
    @PutMapping("internal/merge")
    @Transactional
    public MetaData update(@Validated @RequestBody MetaDataUpdate metaDataUpdate, APIUser apiUser) throws
            JsonProcessingException {
        String name = apiUser.getName();
        return doMergeUpdate(metaDataUpdate, name, "Internal API merge", true).get();
    }

    private Optional<MetaData> doMergeUpdate(MetaDataUpdate metaDataUpdate, String name, String revisionNote, boolean forceNewRevision)
            throws JsonProcessingException {
        String id = metaDataUpdate.getId();
        MetaData previous = metaDataRepository.findById(id, metaDataUpdate.getType());
        checkNull(metaDataUpdate.getType(), id, previous);

        previous.revision(UUID.randomUUID().toString());

        MetaData metaData = metaDataRepository.findById(id, metaDataUpdate.getType());
        metaData.promoteToLatest(name, revisionNote);
        metaData.merge(metaDataUpdate);

        if (!CollectionUtils.isEmpty(metaDataUpdate.getExternalReferenceData())) {
            metaData.getData().putAll(metaDataUpdate.getExternalReferenceData());
        }
        metaData = metaDataHook.prePut(previous, metaData);
        metaData = validate(metaData);
        //Only save and update if there are changes
        boolean somethingChanged = !metaData.metaDataFields().equals(previous.metaDataFields());

        if (somethingChanged || forceNewRevision) {
            metaDataRepository.save(previous);
            metaDataRepository.update(metaData);

            LOG.info("Merging new metaData {} by {}", metaData.getId(), name);

            return Optional.of(this.get(metaData.getType(), metaData.getId()));
        } else {
            return Optional.empty();
        }
    }


    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/client/restoreDeleted")
    @Transactional
    public MetaData restoreDeleted(@Validated @RequestBody RevisionRestore revisionRestore,
                                   FederatedUser federatedUser) throws JsonProcessingException {
        MetaData revision = metaDataRepository.findById(revisionRestore.getId(), revisionRestore.getType());

        MetaData parent = metaDataRepository.findById(revision.getRevision().getParentId(),
                revisionRestore.getParentType());

        if (parent != null) {
            throw new IllegalArgumentException("Parent is not null");
        }
        String newId = revision.getRevision().getParentId();
        revision.getRevision().deTerminate(newId);
        metaDataRepository.update(revision);

        revision.restoreToLatest(newId, 0L, federatedUser.getUid(),
                revision.getRevision().getNumber(), revisionRestore.getParentType());
        //It might be that the revision is no longer valid as metaData configuration has changed
        revision = validate(revision);
        metaDataRepository.save(revision);

        LOG.info("Restored deleted revision {} with Id {} by {}", revisionRestore, revision.getId(), federatedUser
                .getUid());

        return revision;
    }


    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/client/restoreRevision")
    @Transactional
    public MetaData restoreRevision(@Validated @RequestBody RevisionRestore revisionRestore,
                                    FederatedUser federatedUser) throws JsonProcessingException {
        MetaData revision = metaDataRepository.findById(revisionRestore.getId(), revisionRestore.getType());

        MetaData parent = metaDataRepository.findById(revision.getRevision().getParentId(),
                revisionRestore.getParentType());

        revision.restoreToLatest(parent.getId(), parent.getVersion(), federatedUser.getUid(),
                parent.getRevision().getNumber(), revisionRestore.getParentType());
        //It might be that the revision is no longer valid as metaData configuration has changed
        revision = validate(revision);
        metaDataRepository.update(revision);

        parent.revision(UUID.randomUUID().toString());
        metaDataRepository.save(parent);

        LOG.info("Restored revision {} with Id {} by {}", revisionRestore, revision.getId(), federatedUser.getUid());

        return revision;
    }

    @GetMapping("/client/revisions/{type}/{parentId}")
    public List<MetaData> revisions(@PathVariable("type") String type, @PathVariable("parentId") String parentId) {
        return metaDataRepository.revisions(type.concat(REVISION_POSTFIX), parentId);
    }


    @GetMapping("/client/autocomplete/{type}")
    public Map<String, List<Map>> autoCompleteEntities(@PathVariable("type") String type, @RequestParam("query") String query) {
        List<Map> suggestions = metaDataRepository.autoComplete(type, query);
        Map<String, List<Map>> results = new HashMap<>();
        results.put("suggestions", suggestions);
        if (suggestions.isEmpty() && entityTypesSuggestions.contains(type)) {
            List<Map> alternatives = new ArrayList<>();
            entityTypesSuggestions.stream().filter(s -> !s.equals(type))
                    .forEach(s -> alternatives.addAll(metaDataRepository.autoComplete(s, query)));
            results.put("alternatives", alternatives);
        }
        return results;
    }

    @GetMapping("/client/whiteListing/{type}")
    public List<Map> whiteListing(@PathVariable("type") String type,
                                  @RequestParam(value = "state") String state) {
        return metaDataRepository.whiteListing(type, state);
    }

    @PostMapping({"/client/uniqueEntityId/{type}", "/internal/uniqueEntityId/{type}"})
    public List<Map> uniqueEntityId(@PathVariable("type") String type,
                                    @RequestBody Map<String, Object> properties) {
        EntityType entityType = EntityType.fromType(type);
        List<Map> results;
        if (entityType.equals(EntityType.IDP) || entityType.equals(EntityType.STT)) {
            results = metaDataRepository.search(type, properties, new ArrayList<>(), false, true);
        } else {
            results = metaDataRepository.search(entityType.getType(), properties, new ArrayList<>(), false, true);
            results.addAll(metaDataRepository.search(entityType.equals(EntityType.RP) ? EntityType.SP.getType() : EntityType.RP.getType(), properties, new ArrayList<>(), false, true));
        }
        return results;
    }

    @PostMapping({"/client/search/{type}", "/internal/search/{type}"})
    public List<Map> searchEntities(@PathVariable("type") String type,
                                    @RequestBody Map<String, Object> properties,
                                    @RequestParam(required = false, defaultValue = "false") boolean nested) {
        List requestedAttributes = (List) properties.getOrDefault(REQUESTED_ATTRIBUTES, new
                ArrayList<String>());
        Boolean allAttributes = (Boolean) properties.getOrDefault(ALL_ATTRIBUTES, false);
        Boolean logicalOperatorIsAnd = (Boolean) properties.getOrDefault(LOGICAL_OPERATOR_IS_AND, true);
        properties.remove(REQUESTED_ATTRIBUTES);
        properties.remove(ALL_ATTRIBUTES);
        properties.remove(LOGICAL_OPERATOR_IS_AND);
        List<Map> search = metaDataRepository.search(type, properties, requestedAttributes, allAttributes,
                logicalOperatorIsAnd);
        return nested ? search.stream().map(m -> exporter.nestMetaData(m, type)).collect(toList()) : search;
    }

    @GetMapping({"/client/rawSearch/{type}", "/internal/rawSearch/{type}"})
    public List<MetaData> rawSearch(@PathVariable("type") String type, @RequestParam("query") String query) throws
            UnsupportedEncodingException {
        if (query.startsWith("%")) {
            query = URLDecoder.decode(query, "UTF-8");
        }
        return metaDataRepository.findRaw(type, query);
    }

    private MetaData validate(MetaData metaData) throws JsonProcessingException {
        metaData = metaDataHook.preValidate(metaData);
        metaDataAutoConfiguration.validate(metaData.getData(), metaData.getType());
        return metaData;
    }

    @Secured("WRITE")
    @PutMapping(value = "/internal/connectWithoutInteraction")
    public HttpEntity<HttpStatus> connectWithoutInteraction(@RequestBody Map<String, String> connectionData, APIUser apiUser) throws JsonProcessingException {
        LOG.debug("connectWithoutInteraction, connectionData: " + connectionData);

        String idpEntityId = connectionData.get("idpId");
        MetaData idp = findByEntityId(idpEntityId, EntityType.IDP.getType());

        String spEntityId = connectionData.get("spId");
        String spType = connectionData.get("spType");
        MetaData sp = findByEntityId(spEntityId, spType);

        //We can connect automatically if the SP allows it or the IdP and SP share the institution ID
        String dashboardConnectType = (String) sp.metaDataFields().get(DASHBOARD_CONNECT_OPTION);
        boolean connectWithoutInteraction = StringUtils.hasText(dashboardConnectType) && DashboardConnectOption.fromType(dashboardConnectType).connectWithoutInteraction();

        Object idpInstitutionId = idp.metaDataFields().get("coin:institution_id");
        Object spInstitutionId = sp.metaDataFields().get("coin:institution_id");
        boolean shareInstitutionId = idpInstitutionId != null && idpInstitutionId.equals(spInstitutionId) && !"connect_with_interaction".equals("dashboardConnectType");
        if (!connectWithoutInteraction && !shareInstitutionId) {
            throw new EndpointNotAllowed(
                    String.format("SP %s does not allow an automatic connection with IdP %s. SP dashboardConnectType: %s, idpInstitutionId: %s, spInstitutionId %s",
                            spEntityId, idpEntityId, dashboardConnectType, idpInstitutionId, spInstitutionId));
        }

        this.addAllowedEntity(sp, idpEntityId, connectionData, apiUser);
        this.addAllowedEntity(idp, spEntityId, connectionData, apiUser);

        databaseController.doPush();

        return new HttpEntity<>(HttpStatus.OK);
    }


    @Secured("WRITE")
    @PutMapping("internal/oidc/merge")
    public List<MetaData> oidcMerge(@RequestBody List<String> spEntityIds, APIUser apiUser) throws JsonProcessingException {
        LOG.debug("Starting OIDC Merge by {} for spEntityIds {}", apiUser.getName(), spEntityIds);

        List<MetaData> metaDataResult = new ArrayList<>();

        for (String spEntityId : spEntityIds) {
            MetaData sp = findByEntityId(spEntityId, EntityType.SP.getType());
            Map<String, Object> data = sp.getData();
            String entityId = (String) data.get("entityid");

            String openIdClientId = translateServiceProviderEntityId(entityId);
            Optional<Client> clientOptional = openIdConnect.getClient(openIdClientId);

            if (!clientOptional.isPresent()) {
                continue;
            }
            Client client = clientOptional.get();

            String newEntityId = entityId.replaceFirst("^(http[s]?://)", "");
            if (newEntityId.equals(entityId)) {
                throw new DuplicateEntityIdException(
                        String.format("Could not merge due to entityId conflict. Current: %s, new: %s",
                                entityId, newEntityId));
            }
            data.put("entityid", newEntityId);

            Map<String, Object> metaDataFields = (Map) data.get("metaDataFields");
            String secret = UUID.randomUUID().toString();

            metaDataFields.put("secret", secret);

            Map<String, Object> schema = metaDataAutoConfiguration.schemaRepresentation(EntityType.RP);
            Map topLevelProperties = Map.class.cast(schema.get("properties"));
            Map metaDataFieldProperties = Map.class.cast(topLevelProperties.get("metaDataFields"));
            Map<String, Map> properties = (Map) metaDataFieldProperties.get("properties");
            Map<String, Map> patternProperties = (Map) metaDataFieldProperties.get("patternProperties");

            List<String> validGrants = (List<String>) ((Map) properties.get("grants").get("items")).get("enum");
            metaDataFields.put("grants", client.getGrantTypes().stream().filter(validGrants::contains).collect(toList()));

            List<String> validScopes = metaDataRepository.getMongoTemplate().findAll(Scope.class).stream().map(Scope::getName).collect(toList());
            Set<String> clientScopes = client.getScope();
            List<String> scopes = clientScopes.stream().filter(validScopes::contains).collect(toList());
            scopes = CollectionUtils.isEmpty(scopes) ? Collections.singletonList("openid") : scopes;
            metaDataFields.put("scopes", scopes);

            metaDataFields.put("accessTokenValidity", client.getAccessTokenValiditySeconds());
            metaDataFields.put("refreshTokenValidity", client.getRefreshTokenValiditySeconds());

            ArrayList<String> redirectUris = CollectionUtils.isEmpty(client.getRedirectUris()) ? new ArrayList<>() : new ArrayList<>(client.getRedirectUris());

            redirectUris.remove("https://authz-playground." + this.baseDomain + "/redirect");
            redirectUris.add("https://oidc-playground." + this.baseDomain + "/redirect");
            metaDataFields.put("redirectUrls", redirectUris);

            //remove all non-OIDC attributes
            metaDataFields.entrySet().removeIf(entry -> !(properties.containsKey(entry.getKey()) ||
                    patternProperties.keySet().stream().anyMatch(prop -> Pattern.compile(prop).matcher(entry.getKey()).matches())));
            data.entrySet().removeIf(entry -> !topLevelProperties.containsKey(entry.getKey()));

            //Reminiscent of the Janus past
            data.put("type", "oidc10-rp");
            data.put("revisionnote", String.format("Connection created by OIDC Merge for %s on request of %s", spEntityId, apiUser.getName()));

            MetaData oidcRP = new MetaData(EntityType.RP.getType(), data);
            oidcRP = this.doPost(oidcRP, apiUser.getName(), false);

            //There is a hook which hashes the secret and the recipient needs the unhashed secret
            oidcRP.metaDataFields().put("secret", secret);
            metaDataResult.add(oidcRP);

            //Now update all IdP's that have the SP in the allowedEntities.name
            List<MetaData> identityProviders = metaDataRepository.findRaw(EntityType.IDP.getType(),
                    String.format("{\"data.%s.name\" : \"%s\"}", "allowedEntities", spEntityId));
            identityProviders.forEach(idp -> {
                MetaData previous = metaDataRepository.findById(idp.getId(), idp.getType());
                previous.revision(UUID.randomUUID().toString());
                metaDataRepository.save(previous);

                List<Map<String, String>> allowedEntities = (List<Map<String, String>>) idp.getData().get("allowedEntities");
                allowedEntities.add(Collections.singletonMap("name", newEntityId));

                idp.promoteToLatest(apiUser.getName(),
                        String.format("Added OIDC RP to allowedEntities during API internal/oidc/merge for %s by %s", spEntityId, apiUser.getName()));
                metaDataRepository.update(idp);
            });

        }
        //EB needs to receive the new entry
        if (metaDataResult.size() > 0) {
            databaseController.doPush();
        }

        return metaDataResult;
    }

    private void addAllowedEntity(MetaData metaData, String entityId, Map<String, String> connectionData, APIUser apiUser) throws JsonProcessingException {
        Map<String, Object> data = metaData.getData();
        List<Map<String, String>> allowedEntities = (List<Map<String, String>>) data.getOrDefault("allowedEntities", new ArrayList<Map<String, String>>());
        boolean allowedAll = (boolean) data.getOrDefault("allowedall", true);

        if (!allowedAll && allowedEntities.stream().noneMatch(allowedEntity -> allowedEntity.get("name").equals(entityId))) {
            allowedEntities.add(Collections.singletonMap("name", entityId));
            data.put("allowedEntities", allowedEntities);
            data.put("revisionnote", String.format("Connection created by Dashboard on request of %s - %s", connectionData.get("user"), connectionData.get("userUrn")));
            doPut(metaData, apiUser.getName(), false);
        }
    }

    private MetaData findByEntityId(String entityId, String type) {
        List<Map> searchResults = uniqueEntityId(type, Collections.singletonMap("entityid", entityId));
        if (CollectionUtils.isEmpty(searchResults)) {
            throw new ResourceNotFoundException(String.format("Type %s with entityId %s does not exists", type, entityId));
        }
        return metaDataRepository.findById((String) searchResults.get(0).get("_id"), type);
    }
}
