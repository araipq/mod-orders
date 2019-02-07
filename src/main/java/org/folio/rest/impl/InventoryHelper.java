package org.folio.rest.impl;

import io.vertx.core.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import me.escoffier.vertx.completablefuture.VertxCompletableFuture;
import org.apache.commons.collections4.ListUtils;
import org.folio.orders.rest.exceptions.InventoryException;
import org.folio.orders.rest.exceptions.ValidationException;
import org.folio.rest.jaxrs.model.CompositePoLine;
import org.folio.rest.jaxrs.model.Details;
import org.folio.rest.jaxrs.model.ProductId;
import org.folio.rest.tools.client.Response;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static java.util.Collections.singletonList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static me.escoffier.vertx.completablefuture.VertxCompletableFuture.allOf;
import static org.folio.orders.utils.HelperUtils.calculateInventoryItemsQuantity;
import static org.folio.orders.utils.HelperUtils.encodeQuery;
import static org.folio.orders.utils.HelperUtils.groupLocationsById;
import static org.folio.orders.utils.HelperUtils.handleGetRequest;
import static org.folio.orders.utils.HelperUtils.verifyAndExtractBody;
import static org.folio.rest.impl.AbstractHelper.ID;

public class InventoryHelper {

  static final String INSTANCE_SOURCE = "source";
  static final String INSTANCE_TITLE = "title";
  private static final String INSTANCE_EDITIONS = "editions";
  static final String INSTANCE_STATUS_ID = "statusId";
  static final String INSTANCE_INSTANCE_TYPE_ID = "instanceTypeId";
  static final String INSTANCE_PUBLISHER = "publisher";
  static final String INSTANCE_DATE_OF_PUBLICATION = "dateOfPublication";
  static final String INSTANCE_PUBLICATION = "publication";
  static final String INSTANCE_IDENTIFIER_TYPE_ID = "identifierTypeId";
  static final String INSTANCE_IDENTIFIERS = "identifiers";
  static final String INSTANCE_IDENTIFIER_TYPE_VALUE = "value";
  static final String HOLDING_INSTANCE_ID = "instanceId";
  static final String HOLDING_PERMANENT_LOCATION_ID = "permanentLocationId";
  static final String ITEM_HOLDINGS_RECORD_ID = "holdingsRecordId";
  static final String ITEM_STATUS = "status";
  static final String ITEM_STATUS_NAME = "name";
  static final String ITEM_STATUS_ON_ORDER = "On order";
  static final String ITEM_MATERIAL_TYPE_ID = "materialTypeId";
  static final String ITEM_PERMANENT_LOAN_TYPE_ID = "permanentLoanTypeId";
  static final String ITEM_PURCHASE_ORDER_LINE_IDENTIFIER = "purchaseOrderLineIdentifier";

  private static final String HOLDINGS_RECORDS = "holdingsRecords";
  private static final String IDENTIFIER_TYPES = "identifierTypes";
  private static final String INSTANCES = "instances";
  private static final String ITEMS = "items";
  private static final String LOAN_TYPES = "loantypes";

  private static final String DEFAULT_INSTANCE_TYPE_CODE = "zzz";
  private static final String DEFAULT_STATUS_CODE = "temp";
  private static final String DEFAULT_LOAN_TYPE_NAME = "Can circulate";
  private static final String LOCATION_HEADER = "Location";
  private static final String LOOKUP_INSTANCES_ENDPOINT = "/inventory/instances?query=%s&lang=%s";
  private static final String CREATE_INSTANCE_ENDPOINT = "/inventory/instances?lang=%s";
  private static final String LOOKUP_ITEMS_QUERY = "purchaseOrderLineIdentifier==%s and holdingsRecordId==%s";
  private static final String LOOKUP_ITEMS_ENDPOINT = "/item-storage/items?query=%s&limit=%d&lang=%s";
  private static final String CREATE_ITEM_ENDPOINT = "/item-storage/items?lang=%s";
  private static final String HOLDINGS_LOOKUP_QUERY = "instanceId==%s and permanentLocationId==%s";
  private static final String HOLDINGS_LOOKUP_ENDPOINT = "/holdings-storage/holdings?query=%s&limit=1&lang=%s";
  private static final String HOLDINGS_CREATE_ENDPOINT = "/holdings-storage/holdings?lang=%s";

  private static final Logger logger = LoggerFactory.getLogger(InventoryHelper.class);

  private final HttpClientInterface httpClient;
  private final Map<String, String> okapiHeaders;
  private final Context ctx;
  private final String lang;

  InventoryHelper(HttpClientInterface httpClient, Map<String, String> okapiHeaders, Context ctx, String lang) {
    this.httpClient = httpClient;
    this.okapiHeaders = okapiHeaders;
    this.ctx = ctx;
    this.lang = lang;
  }

  public CompletableFuture<CompositePoLine> handleInstanceRecord(CompositePoLine compPOL) {
    return getProductTypesMap(compPOL)
      .thenCompose(productTypesMap -> getInstanceRecord(compPOL, productTypesMap))
      .thenApply(compPOL::withInstanceId);
  }

  /**
   * Returns list of item id's corresponding to given PO line.
   * Items are either retrieved from Inventory or new ones are created if no corresponding item records exist yet.
   *
   * @param compPOL   PO line to retrieve/create Item Records for. At this step PO Line must contain instance Id
   * @return future with list of item id's
   */
  public CompletableFuture<List<String>> handleItemRecords(CompositePoLine compPOL) {
    CompletableFuture<List<String>> result = new VertxCompletableFuture<>(ctx);
    List<CompletableFuture<List<String>>> itemsPerHolding = new ArrayList<>();

    // Group all locations by location id because the holding should be unique for different locations
    groupLocationsById(compPOL)
      .forEach((locationId, locations) -> {
        int expectedQuantity = calculateInventoryItemsQuantity(compPOL, locations);
        // For some cases items might not be created e.g. Electronic resource with create inventory set to false
        if (expectedQuantity > 0) {
          itemsPerHolding.add(
            // Search for or create a new holding and then create items for this holding
            getOrCreateHoldingsRecord(compPOL, locationId)
              .thenCompose(holdingId -> handleItemRecords(compPOL, holdingId, expectedQuantity))
          );
        }
      });

    // Wait for all requests completion and collect all items id's. In case any failed, complete resulting future with the exception
    allOf(itemsPerHolding.toArray(new CompletableFuture[0]))
      .thenAccept(v -> {
        List<String> allIds = itemsPerHolding
          .stream()
          // The CompletableFuture::join can be used safely because the `allOf` is used
          .map(CompletableFuture::join)
          .flatMap(List::stream)
          .collect(toList());

        result.complete(allIds);
      })
      .exceptionally(exc -> {
        result.completeExceptionally(exc);
        return null;
      });

    return result;
  }

  private CompletableFuture<String> getOrCreateHoldingsRecord(CompositePoLine compPOL, String locationId) {
    String instanceId = compPOL.getInstanceId();

    String query = encodeQuery(String.format(HOLDINGS_LOOKUP_QUERY, instanceId, locationId), logger);
    String endpoint = String.format(HOLDINGS_LOOKUP_ENDPOINT, query, lang);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger)
          .thenCompose(holdings -> {
            if (!holdings.getJsonArray(HOLDINGS_RECORDS).isEmpty()) {
              return completedFuture(extractId(getFirstObjectFromResponse(holdings, HOLDINGS_RECORDS)));
            }
            return createHoldingsRecord(instanceId, locationId);
          });
  }

  private CompletableFuture<String> createHoldingsRecord(String instanceId, String locationId) {
    JsonObject holdingsRecJson = new JsonObject();
    holdingsRecJson.put(HOLDING_INSTANCE_ID, instanceId);
    holdingsRecJson.put(HOLDING_PERMANENT_LOCATION_ID, locationId);

    return createRecordInStorage(holdingsRecJson, String.format(HOLDINGS_CREATE_ENDPOINT, lang));
  }

  /**
   * Returns list of item id's corresponding to given PO line.
   * Items are either retrieved from Inventory or new ones are created if no corresponding item records exist yet.
   *
   * @param compPOL   PO line to retrieve/create Item Records for
   * @param holdingId holding uuid from the inventory
   * @param expectedQuantity expected items quantity for the holding
   * @return future with list of item id's
   */
  private CompletableFuture<List<String>> handleItemRecords(CompositePoLine compPOL, String holdingId, int expectedQuantity) {
    // Search for already existing items
    return searchForExistingItems(compPOL, holdingId, expectedQuantity)
      .thenCompose(existingItemIds -> {
        // Create only missing items
        int remainingItemsQuantity = expectedQuantity - existingItemIds.size();
        return createMissingItems(compPOL, holdingId, remainingItemsQuantity)
          .thenApply(createdItemIds -> {
            List<String> allItemIds = ListUtils.union(existingItemIds, createdItemIds);

            // In case no items created, return an exception because nothing can be done at this stage
            if (allItemIds.isEmpty()) {
              throw new InventoryException(String.format("No items created for PO Line with %s id", compPOL.getId()));
            }

            return allItemIds;
          });
        }
      );
  }

  /**
   * Retrieves product type details associated with given PO line
   * and builds 'product type name' -> 'product type id' map.
   *
   * @param compPOL the PO line to retrieve product type details for
   * @return product types map
   */
  private CompletableFuture<Map<String, String>> getProductTypesMap(CompositePoLine compPOL) {
    // do not fail if no productId is provided, should be enforced on schema level if it's required
    if (compPOL.getDetails() == null || compPOL.getDetails().getProductIds().isEmpty()) {
      return completedFuture(Collections.emptyMap());
    }

    String endpoint = compPOL.getDetails().getProductIds().stream()
      .map(productId -> String.format("name==%s", productId.getProductIdType().toString()))
      .collect(joining(" or ", "/identifier-types?query=", ""));

    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger)
      .thenApply(productTypes -> {
        if (productTypes.getJsonArray(IDENTIFIER_TYPES).size() != compPOL.getDetails().getProductIds().size()) {
          throw new ValidationException("Invalid product type(s) is specified for the PO line with id " + compPOL.getId());
        }
        return productTypes;
      })
      .thenApply(productTypes -> productTypes.getJsonArray(IDENTIFIER_TYPES).stream()
        .collect(toMap(jsonObj -> ((JsonObject) jsonObj).getString("name"),
          jsonObj -> ((JsonObject) jsonObj).getString("id"),
          (k1, k2) -> k1)));
  }

  /**
   * Returns Id of the Instance Record corresponding to given PO line.
   * Instance record is either retrieved from Inventory or a new one is created if no corresponding Record exists.
   *
   * @param compPOL PO line to retrieve Instance Record Id for
   * @param productTypesMap product types Map used to build Inventory query
   * @return future with Instance Id
   */
  private CompletionStage<String> getInstanceRecord(CompositePoLine compPOL, Map<String, String> productTypesMap) {
    // proceed with new Instance Record creation if no productId is provided
    if (compPOL.getDetails() == null || compPOL.getDetails().getProductIds().isEmpty()) {
      return createInstanceRecord(compPOL, productTypesMap);
    }

    String query = compPOL.getDetails().getProductIds().stream()
      .map(productId -> buildProductIdQuery(productId, productTypesMap))
      .collect(joining(" or "));

    // query contains special characters so must be encoded before submitting
    String endpoint = String.format(LOOKUP_INSTANCES_ENDPOINT, encodeQuery(query, logger), lang);

    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger)
      .thenCompose(instances -> {
        if (!instances.getJsonArray(INSTANCES).isEmpty()) {
          return completedFuture(extractId(getFirstObjectFromResponse(instances, INSTANCES)));
        }
        return createInstanceRecord(compPOL, productTypesMap);
      });
  }

  /**
   * Creates Instance Record in Inventory and returns its Id.
   *
   * @param compPOL PO line to create Instance Record for
   * @param productTypesMap product types Map used to build Instance Record json object
   * @return id of newly created Instance Record
   */
  private CompletableFuture<String> createInstanceRecord(CompositePoLine compPOL, Map<String, String> productTypesMap) {
    JsonObject lookupObj = new JsonObject();
    CompletableFuture<Void> instanceTypeFuture = getInstanceType(DEFAULT_INSTANCE_TYPE_CODE)
      .thenAccept(lookupObj::mergeIn);
    CompletableFuture<Void> statusFuture = getStatus(DEFAULT_STATUS_CODE)
      .thenAccept(lookupObj::mergeIn);

    return allOf(ctx, instanceTypeFuture, statusFuture)
      .thenApply(v -> buildInstanceRecordJsonObject(compPOL, productTypesMap, lookupObj))
      .thenCompose(instanceRecJson -> createRecordInStorage(instanceRecJson, String.format(CREATE_INSTANCE_ENDPOINT, lang)));
  }

  private CompletableFuture<JsonObject> getInstanceType(String typeName) {
    String endpoint = String.format("/instance-types?query=code==%s", typeName);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger);
  }

  private CompletableFuture<JsonObject> getStatus(String statusCode) {
    String endpoint = String.format("/instance-statuses?query=code==%s", statusCode);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger);
  }

  private String buildProductIdQuery(ProductId productId, Map<String, String> productTypes) {
    return String.format("(identifiers adj \"\\\"identifierTypeId\\\": \\\"%s\\\"\" " +
        "and identifiers adj \"\\\"value\\\": \\\"%s\\\"\")",
      productTypes.get(productId.getProductIdType().toString()),
      productId.getProductId());
  }

  private JsonObject buildInstanceRecordJsonObject(CompositePoLine compPOL, Map<String, String> productTypes, JsonObject lookupObj) {
    JsonObject instance = new JsonObject();

    // MODORDERS-145 The Source and source code are required by schema
    instance.put(INSTANCE_SOURCE, compPOL.getSource().getCode());
    if (compPOL.getTitle() != null) {
      instance.put(INSTANCE_TITLE, compPOL.getTitle());
    }
    if (compPOL.getEdition() != null) {
      instance.put(INSTANCE_EDITIONS, new JsonArray(singletonList(compPOL.getEdition())));
    }
    instance.put(INSTANCE_STATUS_ID, lookupObj.getJsonArray("instanceStatuses").getJsonObject(0).getString(ID));
    instance.put(INSTANCE_INSTANCE_TYPE_ID, lookupObj.getJsonArray("instanceTypes").getJsonObject(0).getString(ID));

    if (compPOL.getPublisher() != null || compPOL.getPublicationDate() != null) {
      JsonObject publication = new JsonObject();
      publication.put(INSTANCE_PUBLISHER, compPOL.getPublisher());
      publication.put(INSTANCE_DATE_OF_PUBLICATION, compPOL.getPublicationDate());
      instance.put(INSTANCE_PUBLICATION, new JsonArray(singletonList(publication)));
    }

    if (compPOL.getDetails() != null && compPOL.getDetails().getProductIds() != null) {
      List<JsonObject> identifiers =
        compPOL.getDetails()
               .getProductIds()
               .stream()
               .map(pId -> {
                 JsonObject identifier = new JsonObject();
                 identifier.put(INSTANCE_IDENTIFIER_TYPE_ID, productTypes.get(pId.getProductIdType()
                                                                                 .toString()));
                 identifier.put(INSTANCE_IDENTIFIER_TYPE_VALUE, pId.getProductId());
                 return identifier;
               })
               .collect(toList());
      instance.put(INSTANCE_IDENTIFIERS, new JsonArray(identifiers));
    }
    return instance;
  }

  /**
   * Search for items which might be already created for the PO line
   * @param compPOL PO line to retrieve Item Records for
   * @param holdingId holding uuid from the inventory
   * @param expectedQuantity expected quantity of the items for combination of the holding and PO Line uuid's from the inventory
   * @return future with list of item id's
   */
  private CompletableFuture<List<String>> searchForExistingItems(CompositePoLine compPOL, String holdingId, int expectedQuantity) {
    String query = encodeQuery(String.format(LOOKUP_ITEMS_QUERY, compPOL.getId(), holdingId), logger);
    String endpoint = String.format(LOOKUP_ITEMS_ENDPOINT, query, expectedQuantity, lang);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger)
      .thenApply(items -> {
        List<String> itemIds = collectItemIds(items);
        logger.debug("{} existing items found out of {} for PO Line with '{}' id", itemIds.size(), expectedQuantity, compPOL.getId());
        return itemIds;
      });
  }

  /**
   * Validates if the json object contains items and extracts ids or returns empty list
   * @param itemEntries {@link JsonObject} representing item storage response
   * @return list of the item ids if any item returned
   */
  private List<String> collectItemIds(JsonObject itemEntries) {
    return Optional.ofNullable(itemEntries.getJsonArray(ITEMS))
                   .map(items -> items.stream()
                                      .map(item -> (JsonObject) item)
                                      .map(this::extractId)
                                      .collect(toList()))
                   .orElseGet(Collections::emptyList);
  }

  /**
   * Creates Items in the inventory based on the PO line data.
   *
   * @param compPOL PO line to create Instance Record for
   * @param holdingId holding id
   * @param quantity expected number of items to create
   * @return id of newly created Instance Record
   */
  private CompletableFuture<List<String>> createMissingItems(CompositePoLine compPOL, String holdingId, int quantity) {
    if (quantity > 0) {
      return buildItemRecordJsonObject(compPOL, holdingId)
        .thenCompose(itemData -> {
          logger.debug("Creating {} items for PO Line with '{}' id", quantity, compPOL.getId());
          return createItemRecords(itemData, quantity);
        });
    } else {
      return completedFuture(Collections.emptyList());
    }
  }

  /**
   * Wait for item creation requests completion and filter failed items if any
   * @param itemRecord item record to be created
   * @param expectedCount count of the items to be created
   * @return completable future with list of item id's
   */
  private CompletableFuture<List<String>> createItemRecords(JsonObject itemRecord, int expectedCount) {
    List<CompletableFuture<String>> futures = new ArrayList<>(expectedCount);
    for (int i = 0; i < expectedCount; i++) {
      futures.add(createItemInInventory(itemRecord));
    }

    return allOf(futures.toArray(new CompletableFuture[0]))
      .thenApply(v -> futures.stream()
                             .map(CompletableFuture::join)
                             // In case item creation failed, null is returned as a result instead of id
                             .filter(Objects::nonNull)
                             .collect(toList())
      );
  }

  /**
   * Creates new entry in the inventory storage based on the PO line data.
   *
   * @param itemData json to post
   * @return id of newly created entity Record
   */
  private CompletableFuture<String> createItemInInventory(JsonObject itemData) {
    return createRecordInStorage(itemData, String.format(CREATE_ITEM_ENDPOINT, lang))
      // In case item creation failed, return null instead of id
      .exceptionally(throwable -> null);
  }

  /**
   * A common method to create a new entry in the inventory storage
   * based on the Json Object and returns the created id.
   *
   * @param recordData json to post
   * @return id of newly created entity Record
   */
  private CompletableFuture<String> createRecordInStorage(JsonObject recordData, String endpoint) {
    CompletableFuture<String> future = new VertxCompletableFuture<>(ctx);
    try {
      if (logger.isDebugEnabled()) {
        logger.debug("Sending 'POST {}' with body: {}", endpoint, recordData.encodePrettily());
      }
      httpClient
        .request(HttpMethod.POST, recordData.toBuffer(), endpoint, okapiHeaders)
        .thenApply(this::verifyAndExtractRecordId)
        .thenAccept(id -> {
          future.complete(id);
          logger.debug("'POST {}' request successfully processed. Record with '{}' id has been created", endpoint, id);
        })
        .exceptionally(throwable -> {
          future.completeExceptionally(throwable);
          logger.error("'POST {}' request failed. Request body: {}", throwable, endpoint, recordData.encodePrettily());
          return null;
        });
    } catch (Exception e) {
      future.completeExceptionally(e);
    }

    return future;
  }

  private String verifyAndExtractRecordId(Response response) {
    logger.debug("Validating received response");

    JsonObject body = verifyAndExtractBody(response);

    String id;
    if (body != null && !body.isEmpty() && body.containsKey(ID)) {
      id = body.getString(ID);
    } else {
      String location = response.getHeaders().get(LOCATION_HEADER);
      id = location.substring(location.lastIndexOf('/') + 1);
    }
    return id;
  }

  /**
   * Builds JsonObject representing inventory item minimal data. The schema is located directly in 'mod-inventory-storage' module.
   *
   * @param compPOL   PO line to create Item Records for
   * @param holdingId holding uuid from the inventory
   * @return item data to be used as request body for POST operation
   */
  private CompletableFuture<JsonObject> buildItemRecordJsonObject(CompositePoLine compPOL, String holdingId) {
    String materialTypeId = getMaterialTypeId(compPOL);
    return getLoanTypeId(DEFAULT_LOAN_TYPE_NAME)
      .thenApply(loanTypeId -> {
        JsonObject itemRecord = new JsonObject();
        itemRecord.put(ITEM_HOLDINGS_RECORD_ID, holdingId);
        itemRecord.put(ITEM_STATUS, new JsonObject().put(ITEM_STATUS_NAME, ITEM_STATUS_ON_ORDER));
        itemRecord.put(ITEM_MATERIAL_TYPE_ID, materialTypeId);
        itemRecord.put(ITEM_PERMANENT_LOAN_TYPE_ID, loanTypeId);
        itemRecord.put(ITEM_PURCHASE_ORDER_LINE_IDENTIFIER, compPOL.getId());
        return itemRecord;
      });
  }

  private String getMaterialTypeId(CompositePoLine compPOL) {
    return Optional.ofNullable(compPOL.getDetails())
                   .map(Details::getMaterialTypes)
                   .flatMap(ids -> ids.stream().findFirst())
                   .orElseThrow(() -> new CompletionException(
                     new ValidationException("The Material Type is required but not available in PO line", "materialTypeRequired")));
  }

  private String extractId(JsonObject json) {
    return json.getString(ID);
  }

  private CompletableFuture<String> getLoanTypeId(String typeName) {
    return getLoanType(typeName)
      .thenApply(this::extractId);
  }

  private CompletableFuture<JsonObject> getLoanType(String typeName) {
    String endpoint = "/loan-types?query=name==" + encodeQuery(typeName, logger);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger)
      .thenApply(response -> getFirstObjectFromResponse(response, LOAN_TYPES));
  }

  /**
   * Accepts response with collection of the elements and tries to extract the first one.
   * In case the response is incorrect or empty, the {@link CompletionException} will be thrown
   * @param response     {@link JsonObject} representing service response which should contain array of objects
   * @param propertyName name of the property which holds array of objects
   * @return the first element of the array
   */
  private JsonObject getFirstObjectFromResponse(JsonObject response, String propertyName) {
    return Optional.ofNullable(response.getJsonArray(propertyName))
                   .flatMap(items -> items.stream().findFirst())
                   .map(item -> (JsonObject) item)
                   .orElseThrow(() -> new CompletionException(new InventoryException(String.format("No records of '%s' can be found", propertyName))));
  }
}
