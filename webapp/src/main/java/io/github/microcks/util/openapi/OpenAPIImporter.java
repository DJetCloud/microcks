/*
 * Copyright The Microcks Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.microcks.util.openapi;

import io.github.microcks.domain.Exchange;
import io.github.microcks.domain.Header;
import io.github.microcks.domain.Metadata;
import io.github.microcks.domain.Operation;
import io.github.microcks.domain.Parameter;
import io.github.microcks.domain.Request;
import io.github.microcks.domain.RequestResponsePair;
import io.github.microcks.domain.Resource;
import io.github.microcks.domain.ResourceType;
import io.github.microcks.domain.Response;
import io.github.microcks.domain.Service;
import io.github.microcks.domain.ServiceType;
import io.github.microcks.util.AbstractJsonRepositoryImporter;
import io.github.microcks.util.DispatchCriteriaHelper;
import io.github.microcks.util.DispatchStyles;
import io.github.microcks.util.MockRepositoryImportException;
import io.github.microcks.util.MockRepositoryImporter;
import io.github.microcks.util.ReferenceResolver;
import io.github.microcks.util.URIBuilder;
import io.github.microcks.util.dispatcher.FallbackSpecification;
import io.github.microcks.util.dispatcher.JsonMappingException;
import io.github.microcks.util.metadata.MetadataExtensions;
import io.github.microcks.util.metadata.MetadataExtractor;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An implementation of MockRepositoryImporter that deals with OpenAPI v3.x.x specification
 * file ; whether encoding into JSON or YAML documents.
 * @author laurent
 */
public class OpenAPIImporter extends AbstractJsonRepositoryImporter implements MockRepositoryImporter {

   /** A simple logger for diagnostic messages. */
   private static Logger log = LoggerFactory.getLogger(OpenAPIImporter.class);

   private static final List<String> VALID_VERBS = Arrays.asList("get", "put", "post", "delete", "options", "head", "patch", "trace");

   private static final String PARAMETERS_NODE = "parameters";
   private static final String PARAMETERS_QUERY_VALUE = "query";
   private static final String CONTENT_NODE = "content";
   private static final String EXAMPLES_NODE = "examples";
   private static final String EXAMPLE_VALUE_NODE = "value";

   /**
    * Build a new importer.
    * @param specificationFilePath The path to local OpenAPI spec file
    * @param referenceResolver An optional resolver for references present into the OpenAPI file
    * @throws IOException if project file cannot be found or read.
    */
   public OpenAPIImporter(String specificationFilePath, ReferenceResolver referenceResolver) throws IOException {
      super(specificationFilePath, referenceResolver);
   }

   @Override
   public List<Service> getServiceDefinitions() throws MockRepositoryImportException {
      List<Service> result = new ArrayList<>();

      // Build a new service.
      Service service = new Service();
      service.setName(rootSpecification.path("info").path("title").asText());
      service.setVersion(rootSpecification.path("info").path("version").asText());
      service.setType(ServiceType.REST);

      // Complete metadata if specified via extension.
      if (rootSpecification.path("info").has(MetadataExtensions.MICROCKS_EXTENSION)) {
         Metadata metadata = new Metadata();
         MetadataExtractor.completeMetadata(metadata, rootSpecification.path("info").path(MetadataExtensions.MICROCKS_EXTENSION));
         service.setMetadata(metadata);
      }

      // Before extraction operations, we need to get and build external reference if we have a resolver.
      initializeReferencedResources(service);

      // Then build its operations.
      service.setOperations(extractOperations());

      result.add(service);
      return result;
   }

   @Override
   public List<Resource> getResourceDefinitions(Service service) {
      List<Resource> results = new ArrayList<>();

      // Build a suitable name.
      String name = service.getName() + "-" + service.getVersion();
      if (Boolean.TRUE.equals(isYaml)) {
         name += ".yaml";
      } else {
         name += ".json";
      }

      // Build a brand new resource just with spec content.
      Resource resource = new Resource();
      resource.setName(name);
      resource.setType(ResourceType.OPEN_API_SPEC);
      results.add(resource);
      // Set the content of main OpenAPI that may have been updated with normalized dependencies with initializeReferencedResources().
      resource.setContent(rootSpecificationContent);

      // Add the external resources that were imported during service discovery.
      results.addAll(externalResources);

      return results;
   }

   @Override
   public List<Exchange> getMessageDefinitions(Service service, Operation operation) throws MockRepositoryImportException {
      Map<Request, Response> result = new HashMap<>();

      // Iterate on specification "paths" nodes.
      Iterator<Entry<String, JsonNode>> paths = rootSpecification.path("paths").fields();
      while (paths.hasNext()) {
         Entry<String, JsonNode> path = paths.next();
         String pathName = path.getKey();
         JsonNode pathValue = followRefIfAny(path.getValue());

         // Find examples fragments defined at the path level.
         Map<String, Map<String, String>> pathPathParametersByExample = extractParametersByExample(pathValue, "path");

         // Iterate on specification path, "verbs" nodes.
         Iterator<Entry<String, JsonNode>> verbs = pathValue.fields();
         while (verbs.hasNext()) {
            Entry<String, JsonNode> verb = verbs.next();
            String verbName = verb.getKey();

            // Find the correct operation.
            if (operation.getName().equals(verbName.toUpperCase() + " " + pathName.trim())) {
               // Find examples fragments defined at the verb level.
               Map<String, Map<String, String>> pathParametersByExample = extractParametersByExample(verb.getValue(), "path");
               pathParametersByExample.putAll(pathPathParametersByExample);
               Map<String, Map<String, String>> queryParametersByExample = extractParametersByExample(verb.getValue(), PARAMETERS_QUERY_VALUE);
               Map<String, Map<String, String>> headerParametersByExample = extractParametersByExample(verb.getValue(), "header");
               Map<String, Request> requestBodiesByExample = extractRequestBodies(verb.getValue());

               // No need to go further if no examples.
               if (verb.getValue().has("responses")) {

                  // If we previously override the dispatcher with a Fallback, we must be sure to get wrapped elements.
                  String rootDispatcher = operation.getDispatcher();
                  String rootDispatcherRules = operation.getDispatcherRules();

                  if (DispatchStyles.FALLBACK.equals(operation.getDispatcher())) {
                     FallbackSpecification fallbackSpec = null;
                     try {
                        fallbackSpec = FallbackSpecification.buildFromJsonString(operation.getDispatcherRules());
                        rootDispatcher = fallbackSpec.getDispatcher();
                        rootDispatcherRules = fallbackSpec.getDispatcherRules();
                     } catch (JsonMappingException e) {
                        log.warn("Operation '{}' has a malformed Fallback dispatcher rules", operation.getName());
                     }
                  }

                  Iterator<Entry<String, JsonNode>> responseCodes = verb.getValue().path("responses").fields();
                  while (responseCodes.hasNext()) {
                     Entry<String, JsonNode> responseCode = responseCodes.next();
                     // Find here potential headers for output of this operation examples.
                     Map<String, List<Header>> headersByExample = extractHeadersByExample(responseCode.getValue());

                     Iterator<Entry<String, JsonNode>> contents = getResponseContent(responseCode.getValue()).fields();
                     while (contents.hasNext()) {
                        Entry<String, JsonNode> content = contents.next();
                        String contentValue = content.getKey();

                        JsonNode examplesNode = content.getValue().path(EXAMPLES_NODE);
                        if (examplesNode.has("$ref")) {
                           examplesNode = followRefIfAny(examplesNode);
                        }

                        Iterator<String> exampleNames = examplesNode.fieldNames();
                        while (exampleNames.hasNext()) {
                           String exampleName = exampleNames.next();
                           JsonNode example = examplesNode.path(exampleName);

                           // We should have everything at hand to build response here.
                           Response response = new Response();
                           response.setName(exampleName);
                           response.setMediaType(contentValue);
                           response.setStatus(responseCode.getKey());
                           response.setContent(getExampleValue(example));
                           if (!responseCode.getKey().startsWith("2")) {
                              response.setFault(true);
                           }
                           List<Header> responseHeaders = headersByExample.get(exampleName);
                           if (responseHeaders != null) {
                              responseHeaders.stream().forEach(response::addHeader);
                           }

                           // Do we have a request for this example?
                           Request request = requestBodiesByExample.get(exampleName);
                           if (request == null) {
                              request = new Request();
                              request.setName(exampleName);
                           }

                           // Complete request accept-type with response content-type.
                           Header header = new Header();
                           header.setName("Accept");
                           HashSet<String> values = new HashSet<>();
                           values.add(contentValue);
                           header.setValues(values);
                           request.addHeader(header);

                           // Do we have to complete request with path parameters?
                           Map<String, String> pathParameters = pathParametersByExample.get(exampleName);
                           if (pathParameters != null) {
                              for (Entry<String, String> paramEntry : pathParameters.entrySet()) {
                                 Parameter param = new Parameter();
                                 param.setName(paramEntry.getKey());
                                 param.setValue(paramEntry.getValue());
                                 request.addQueryParameter(param);
                              }
                           } else if (DispatchStyles.URI_PARTS.equals(operation.getDispatcher())
                                 || DispatchStyles.URI_ELEMENTS.equals(operation.getDispatcher())) {
                              // We've must have at least one path parameters but none...
                              // Do not register this request / response pair.
                              break;
                           }
                           // Do we have to complete request with query parameters?
                           Map<String, String> queryParameters = queryParametersByExample.get(exampleName);
                           if (queryParameters != null) {
                              for (Entry<String, String> paramEntry : queryParameters.entrySet()) {
                                 Parameter param = new Parameter();
                                 param.setName(paramEntry.getKey());
                                 param.setValue(paramEntry.getValue());
                                 request.addQueryParameter(param);
                              }
                           }
                           // Do we have to complete request with header parameters?
                           Map<String, String> headerParameters = headerParametersByExample.get(exampleName);
                           if (headerParameters != null) {
                              for (Entry<String, String> headerEntry : headerParameters.entrySet()) {
                                 header = new Header();
                                 header.setName(headerEntry.getKey());
                                 // Values may be multiple and CSV.
                                 Set<String> headerValues = Arrays.stream(headerEntry.getValue().split(","))
                                       .map(String::trim)
                                       .collect(Collectors.toSet());
                                 header.setValues(headerValues);
                                 request.addHeader(header);
                              }
                           }

                           // Finally, take care about dispatchCriteria and complete operation resourcePaths.
                           String dispatchCriteria = null;
                           String resourcePathPattern = operation.getName().split(" ")[1];

                           if (DispatchStyles.URI_PARAMS.equals(rootDispatcher)) {
                              Map<String, String> queryParams = queryParametersByExample.get(exampleName);
                              dispatchCriteria = DispatchCriteriaHelper
                                    .buildFromParamsMap(rootDispatcherRules, queryParams);
                              // We only need the pattern here.
                              operation.addResourcePath(resourcePathPattern);
                           } else if (DispatchStyles.URI_PARTS.equals(rootDispatcher)) {
                              Map<String, String> parts = pathParametersByExample.get(exampleName);
                              dispatchCriteria = DispatchCriteriaHelper.buildFromPartsMap(rootDispatcherRules, parts);
                              // We should complete resourcePath here.
                              String resourcePath = URIBuilder.buildURIFromPattern(resourcePathPattern, parts);
                              operation.addResourcePath(resourcePath);
                           } else if (DispatchStyles.URI_ELEMENTS.equals(rootDispatcher)) {
                              Map<String, String> parts = pathParametersByExample.get(exampleName);
                              Map<String, String> queryParams = queryParametersByExample.get(exampleName);
                              dispatchCriteria = DispatchCriteriaHelper.buildFromPartsMap(rootDispatcherRules, parts);
                              dispatchCriteria += DispatchCriteriaHelper
                                    .buildFromParamsMap(rootDispatcherRules, queryParams);
                              // We should complete resourcePath here.
                              String resourcePath = URIBuilder.buildURIFromPattern(resourcePathPattern, parts);
                              operation.addResourcePath(resourcePath);
                           }
                           response.setDispatchCriteria(dispatchCriteria);

                           result.put(request, response);
                        }
                     }
                  }
               }
            }
         }
      }

      // Adapt map to list of Exchanges.
      return result.entrySet().stream()
            .map(entry -> new RequestResponsePair(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
   }

   /**
    * Extract the list of operations from Specification.
    */
   private List<Operation> extractOperations() throws MockRepositoryImportException {
      List<Operation> results = new ArrayList<>();

      // Iterate on specification "paths" nodes.
      Iterator<Entry<String, JsonNode>> paths = rootSpecification.path("paths").fields();
      while (paths.hasNext()) {
         Entry<String, JsonNode> path = paths.next();
         String pathName = path.getKey();
         JsonNode pathValue = followRefIfAny(path.getValue());

         // Iterate on specification path, "verbs" nodes.
         Iterator<Entry<String, JsonNode>> verbs = pathValue.fields();
         while (verbs.hasNext()) {
            Entry<String, JsonNode> verb = verbs.next();
            String verbName = verb.getKey();

            // Only deal with real verbs for now.
            if (VALID_VERBS.contains(verbName)) {
               String operationName = verbName.toUpperCase() + " " + pathName.trim();

               Operation operation = new Operation();
               operation.setName(operationName);
               operation.setMethod(verbName.toUpperCase());

               // Complete operation properties if any.
               if (verb.getValue().has(MetadataExtensions.MICROCKS_OPERATION_EXTENSION)) {
                  MetadataExtractor.completeOperationProperties(operation,
                        verb.getValue().path(MetadataExtensions.MICROCKS_OPERATION_EXTENSION));
               }

               // Deal with dispatcher stuffs if needed.
               if (operation.getDispatcher() == null) {
                  if (operationHasParameters(verb.getValue(), PARAMETERS_QUERY_VALUE) && urlHasParts(pathName)) {
                     operation.setDispatcherRules(DispatchCriteriaHelper.extractPartsFromURIPattern(pathName)
                           + " ?? " + extractOperationParams(verb.getValue()));
                     operation.setDispatcher(DispatchStyles.URI_ELEMENTS);
                  } else if (operationHasParameters(verb.getValue(), PARAMETERS_QUERY_VALUE)) {
                     operation.setDispatcherRules(extractOperationParams(verb.getValue()));
                     operation.setDispatcher(DispatchStyles.URI_PARAMS);
                  } else if (urlHasParts(pathName)) {
                     operation.setDispatcherRules(DispatchCriteriaHelper.extractPartsFromURIPattern(pathName));
                     operation.setDispatcher(DispatchStyles.URI_PARTS);
                  } else {
                     operation.addResourcePath(pathName);
                  }
               } else {
                  // If dispatcher has been forced via Metadata, we should still put a generic resourcePath
                  // (maybe containing {} parts) to later force operation matching at the mock controller level.
                  operation.addResourcePath(pathName);
               }

               results.add(operation);
            }
         }
      }
      return results;
   }

   /**
    * Extract parameters within a specification node and organize them by example. Parameter can be of type 'path',
    * 'query', 'header' or 'cookie'. Allow to filter them using parameterType. Key of returned map is example name.
    * Key of value map is param name. Value of value map is param value ;-)
    */
   private Map<String, Map<String, String>> extractParametersByExample(JsonNode node, String parameterType) {
      Map<String, Map<String, String>> results = new HashMap<>();

      Iterator<JsonNode> parameters = node.path(PARAMETERS_NODE).elements();
      while (parameters.hasNext()) {
         JsonNode parameter = followRefIfAny(parameters.next());
         String parameterName = parameter.path("name").asText();

         if (parameter.has("in") && parameter.path("in").asText().equals(parameterType)
               && parameter.has(EXAMPLES_NODE)) {
            Iterator<String> exampleNames = parameter.path(EXAMPLES_NODE).fieldNames();
            while (exampleNames.hasNext()) {
               String exampleName = exampleNames.next();
               JsonNode example = parameter.path(EXAMPLES_NODE).path(exampleName);
               String exampleValue = getExampleValue(example);

               Map<String, String> exampleParams = results.get(exampleName);
               if (exampleParams == null) {
                  exampleParams = new HashMap<>();
                  results.put(exampleName, exampleParams);
               }
               exampleParams.put(parameterName, exampleValue);
            }
         }
      }
      return results;
   }

   /**
    * Extract request bodies within verb specification and organize them by example.
    * Key of returned map is example name. Value is basic Microcks Request object (no query params, no headers)
    */
   private Map<String, Request> extractRequestBodies(JsonNode verbNode) {
      Map<String, Request> results = new HashMap<>();

      JsonNode requestBody = verbNode.path("requestBody");
      Iterator<String> contentTypeNames = requestBody.path(CONTENT_NODE).fieldNames();
      while (contentTypeNames.hasNext()) {
         String contentTypeName = contentTypeNames.next();
         JsonNode contentType = requestBody.path(CONTENT_NODE).path(contentTypeName);

         if (contentType.has(EXAMPLES_NODE)) {
            Iterator<String> exampleNames = contentType.path(EXAMPLES_NODE).fieldNames();
            while (exampleNames.hasNext()) {
               String exampleName = exampleNames.next();
               JsonNode example = contentType.path(EXAMPLES_NODE).path(exampleName);
               String exampleValue = getExampleValue(example);

               // Build and store a request object.
               Request request = new Request();
               request.setName(exampleName);
               request.setContent(exampleValue);

               // We should add a Content-type header here for request body.
               Header header = new Header();
               header.setName("Content-Type");
               HashSet<String> values = new HashSet<>();
               values.add(contentTypeName);
               header.setValues(values);
               request.addHeader(header);

               results.put(exampleName, request);
            }
         }
      }
      return results;
   }

   /**
    * Extract headers within a header specification node and organize them by example.
    * Key of returned map is example name. Value is a list of Microcks Header objects.
    */
   private Map<String, List<Header>> extractHeadersByExample(JsonNode responseNode) {
      Map<String, List<Header>> results = new HashMap<>();

      if (responseNode.has("headers")) {
         JsonNode headersNode = responseNode.path("headers");
         Iterator<String> headerNames = headersNode.fieldNames();

         while (headerNames.hasNext()) {
            String headerName = headerNames.next();
            JsonNode headerNode = headersNode.path(headerName);

            if (headerNode.has(EXAMPLES_NODE)) {
               Iterator<String> exampleNames = headerNode.path(EXAMPLES_NODE).fieldNames();
               while (exampleNames.hasNext()) {
                  String exampleName = exampleNames.next();
                  JsonNode example = headerNode.path(EXAMPLES_NODE).path(exampleName);
                  String exampleValue = getExampleValue(example);

                  // Example may be multiple CSV.
                  Set<String> values = Arrays.stream(exampleValue.split(","))
                        .map(String::trim)
                        .collect(Collectors.toSet());

                  Header header = new Header();
                  header.setName(headerName);
                  header.setValues(values);

                  List<Header> headersForExample = results.get(exampleName);
                  if (headersForExample == null) {
                     headersForExample = new ArrayList<>();
                  }
                  headersForExample.add(header);
                  results.put(exampleName, headersForExample);
               }
            }
         }
      }
      if (responseNode.has("$ref")) {
         JsonNode component = followRefIfAny(responseNode);
         return extractHeadersByExample(component);
      }
      return results;
   }

   /** Get the value of an example. This can be direct value field or those of followed $ref */
   private String getExampleValue(JsonNode example) {
      if (example.has(EXAMPLE_VALUE_NODE)) {
         JsonNode valueNode = followRefIfAny(example.path(EXAMPLE_VALUE_NODE));
         return getValueString(valueNode);
      }
      if (example.has("$ref")) {
         JsonNode component = followRefIfAny(example);
         return getExampleValue(component);
      }
      return null;
   }

   /** Get the content of a response. This can be direct content field or those of followed $ref */
   private JsonNode getResponseContent(JsonNode response) {
      if (response.has("$ref")) {
         JsonNode component = followRefIfAny(response);
         return getResponseContent(component);
      }
      return response.path(CONTENT_NODE);
   }

   /** Build a string representing operation parameters as used in dispatcher rules (param1 && param2)*/
   private String extractOperationParams(JsonNode operation) {
      StringBuilder params = new StringBuilder();
      Iterator<JsonNode> parameters = operation.path(PARAMETERS_NODE).elements();
      while (parameters.hasNext()) {
         JsonNode parameter = followRefIfAny(parameters.next());

         String parameterIn = parameter.path("in").asText();
         if (!"path".equals(parameterIn)) {
            if (params.length() > 0) {
               params.append(" && ");
            }
            params.append(parameter.path("name").asText());
         }
      }
      return params.toString();
   }

   /** Check parameters presence into given operation node. */
   private boolean operationHasParameters(JsonNode operation, String parameterType) {
      if (!operation.has(PARAMETERS_NODE)) {
         return false;
      }
      Iterator<JsonNode> parameters = operation.path(PARAMETERS_NODE).elements();
      while (parameters.hasNext()) {
         JsonNode parameter = followRefIfAny(parameters.next());

         String parameterIn = parameter.path("in").asText();
         if (parameterIn.equals(parameterType)) {
            return true;
         }
      }
      return false;
   }

   /** Check variables parts presence into given url. */
   private static boolean urlHasParts(String url) {
      return (url.indexOf("/:") != -1 || url.indexOf("/{") != -1);
   }
}
