/* Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.discogapic.transformer.java;

import static com.google.api.codegen.util.java.JavaTypeTable.JavaLangResolution.IGNORE_JAVA_LANG_CLASH;

import com.google.api.codegen.config.DiscoApiModel;
import com.google.api.codegen.config.DiscoveryField;
import com.google.api.codegen.config.DiscoveryMethodModel;
import com.google.api.codegen.config.FieldConfig;
import com.google.api.codegen.config.FieldModel;
import com.google.api.codegen.config.FlatteningConfig;
import com.google.api.codegen.config.GapicProductConfig;
import com.google.api.codegen.config.InterfaceModel;
import com.google.api.codegen.config.MethodConfig;
import com.google.api.codegen.config.MethodModel;
import com.google.api.codegen.discogapic.SchemaTransformationContext;
import com.google.api.codegen.discogapic.transformer.DiscoGapicParser;
import com.google.api.codegen.discovery.Method;
import com.google.api.codegen.discovery.Schema;
import com.google.api.codegen.gapic.GapicCodePathMapper;
import com.google.api.codegen.transformer.DiscoGapicInterfaceContext;
import com.google.api.codegen.transformer.FileHeaderTransformer;
import com.google.api.codegen.transformer.ImportTypeTable;
import com.google.api.codegen.transformer.MethodContext;
import com.google.api.codegen.transformer.ModelToViewTransformer;
import com.google.api.codegen.transformer.SchemaTypeNameConverter;
import com.google.api.codegen.transformer.SchemaTypeTable;
import com.google.api.codegen.transformer.StandardImportSectionTransformer;
import com.google.api.codegen.transformer.StaticLangResourceObjectTransformer;
import com.google.api.codegen.transformer.SurfaceNamer;
import com.google.api.codegen.transformer.java.JavaSchemaTypeNameConverter;
import com.google.api.codegen.transformer.java.JavaSurfaceNamer;
import com.google.api.codegen.util.Name;
import com.google.api.codegen.util.SymbolTable;
import com.google.api.codegen.util.java.JavaNameFormatter;
import com.google.api.codegen.util.java.JavaTypeTable;
import com.google.api.codegen.viewmodel.RequestObjectParamView;
import com.google.api.codegen.viewmodel.StaticLangApiMessageFileView;
import com.google.api.codegen.viewmodel.StaticLangApiMessageView;
import com.google.api.codegen.viewmodel.ViewModel;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* Creates the ViewModel for a Discovery Doc request object Java class. */
public class JavaDiscoGapicRequestToViewTransformer
    implements ModelToViewTransformer<DiscoApiModel> {
  private final GapicCodePathMapper pathMapper;
  private final StandardImportSectionTransformer importSectionTransformer =
      new StandardImportSectionTransformer();
  private final FileHeaderTransformer fileHeaderTransformer =
      new FileHeaderTransformer(importSectionTransformer);
  private final StaticLangResourceObjectTransformer resourceObjectTransformer =
      new StaticLangResourceObjectTransformer();
  private final JavaNameFormatter nameFormatter = new JavaNameFormatter();
  private static Set<String> reservedKeywords = new HashSet<>();

  /**
   * Query parameters that may be accepted by any method. See
   * https://cloud.google.com/compute/docs/reference/parameters.
   */
  private static final Map<String, String> STANDARD_QUERY_PARAMS;

  static {
    ImmutableMap.Builder<String, String> queryParams = ImmutableMap.builder();
    queryParams.put("access_token", "OAuth 2.0 token for the current user.");
    queryParams.put(
        "callback", "Name of the JavaScript callback function that handles the response.");
    queryParams.put("fields", "Selector specifying a subset of fields to include in the response.");
    queryParams.put("key", "API key. Required unless you provide an OAuth 2.0 token.");
    queryParams.put("prettyPrint", "Returns response with indentations and line breaks.");
    queryParams.put("quotaUser", "Alternative to userIp.");
    queryParams.put("userIp", "IP address of the end user for whom the API call is being made.");
    STANDARD_QUERY_PARAMS = queryParams.build();
  }

  static {
    reservedKeywords.addAll(JavaNameFormatter.RESERVED_IDENTIFIER_SET);
    reservedKeywords.add("Builder");
  }

  private static final String REQUEST_TEMPLATE_FILENAME = "java/message.snip";

  public JavaDiscoGapicRequestToViewTransformer(GapicCodePathMapper pathMapper) {
    this.pathMapper = pathMapper;
  }

  @Override
  public List<String> getTemplateFileNames() {
    return Arrays.asList(REQUEST_TEMPLATE_FILENAME);
  }

  @Override
  public List<ViewModel> transform(DiscoApiModel model, GapicProductConfig productConfig) {
    List<ViewModel> surfaceRequests = new ArrayList<>();
    String packageName = productConfig.getPackageName();
    SurfaceNamer surfaceNamer = new JavaSurfaceNamer(packageName, packageName, nameFormatter);

    for (InterfaceModel apiInterface : model.getInterfaces(productConfig)) {
      DiscoGapicInterfaceContext context =
          JavaDiscoGapicSurfaceTransformer.newInterfaceContext(
              apiInterface,
              productConfig,
              surfaceNamer,
              createTypeTable(productConfig.getPackageName()));

      for (DiscoveryMethodModel method : context.getSupportedMethods()) {
        RequestObjectParamView params = getRequestObjectParams(context, method);

        SchemaTransformationContext requestContext =
            SchemaTransformationContext.create(
                method.getFullName(), context.getSchemaTypeTable(), context);
        StaticLangApiMessageView requestView = generateRequestClass(requestContext, method, params);
        surfaceRequests.add(generateRequestFile(requestContext, requestView));
      }
    }
    surfaceRequests.sort(
        (ViewModel o1, ViewModel o2) ->
            String.CASE_INSENSITIVE_ORDER.compare(o1.outputPath(), o2.outputPath()));
    return surfaceRequests;
  }

  private RequestObjectParamView getRequestObjectParams(
      DiscoGapicInterfaceContext context, MethodModel method) {
    MethodConfig methodConfig = context.getMethodConfig(method);

    // Generate the ResourceName methods.
    if (methodConfig.isFlattening()) {
      for (FlatteningConfig flatteningGroup : methodConfig.getFlatteningConfigs()) {
        MethodContext flattenedMethodContext =
            context.asFlattenedMethodContext(method, flatteningGroup);
        if (FlatteningConfig.hasAnyRepeatedResourceNameParameter(flatteningGroup)) {
          flattenedMethodContext = flattenedMethodContext.withResourceNamesInSamplesOnly();
        }
        Iterable<FieldConfig> fieldConfigs =
            flattenedMethodContext.getFlatteningConfig().getFlattenedFieldConfigs().values();
        for (FieldConfig fieldConfig : fieldConfigs) {
          if (context.getFeatureConfig().useResourceNameFormatOption(fieldConfig)) {
            return resourceObjectTransformer.generateRequestObjectParam(
                flattenedMethodContext, fieldConfig);
          }
        }
      }
    }
    return null;
  }

  /* Given a message view, creates a top-level message file view. */
  private StaticLangApiMessageFileView generateRequestFile(
      SchemaTransformationContext context, StaticLangApiMessageView messageView) {
    StaticLangApiMessageFileView.Builder apiFile = StaticLangApiMessageFileView.newBuilder();
    apiFile.templateFileName(REQUEST_TEMPLATE_FILENAME);
    addApiImports(context.getImportTypeTable());
    apiFile.schema(messageView);

    String outputPath = pathMapper.getOutputPath(null, context.getDocContext().getProductConfig());
    apiFile.outputPath(outputPath + File.separator + messageView.typeName() + ".java");

    // must be done as the last step to catch all imports
    apiFile.fileHeader(fileHeaderTransformer.generateFileHeader(context));

    return apiFile.build();
  }

  private StaticLangApiMessageView generateRequestClass(
      SchemaTransformationContext context,
      DiscoveryMethodModel method,
      RequestObjectParamView resourceNameView) {
    StaticLangApiMessageView.Builder requestView = StaticLangApiMessageView.newBuilder();

    SymbolTable symbolTable = SymbolTable.fromSeed(reservedKeywords);

    String requestClassId =
        context
            .getNamer()
            .privateFieldName(DiscoGapicParser.getRequestName(method.getDiscoMethod()));
    String requestName =
        nameFormatter.privateFieldName(Name.anyCamel(symbolTable.getNewSymbol(requestClassId)));

    requestView.rawName(requestName); // Serialized name doesn't matter here.
    requestView.name(requestName);
    requestView.docLines(
        context
            .getNamer()
            .getDocLines(
                String.format(
                    "Request object for method %s. %s",
                    method.getDiscoMethod().id(), method.getDescription())));

    String requestTypeName = nameFormatter.publicClassName(Name.anyCamel(requestClassId));
    requestView.typeName(requestTypeName);
    requestView.innerTypeName(requestTypeName);

    List<StaticLangApiMessageView> properties = new LinkedList<>();

    // Add the standard query parameters.
    for (String param : STANDARD_QUERY_PARAMS.keySet()) {
      if (method.getInputField(param) != null) {
        continue;
      }
      StaticLangApiMessageView.Builder paramView = StaticLangApiMessageView.newBuilder();
      paramView.docLines(context.getNamer().getDocLines(STANDARD_QUERY_PARAMS.get(param)));
      paramView.rawName(param);
      paramView.name(symbolTable.getNewSymbol(param));
      paramView.typeName("String");
      paramView.innerTypeName("String");
      paramView.isRequired(false);
      paramView.canRepeat(false);
      paramView.fieldGetFunction(
          context
              .getNamer()
              .getFieldGetFunctionName(
                  DiscoGapicParser.stringToName(param),
                  SurfaceNamer.MapType.NOT_MAP,
                  SurfaceNamer.Cardinality.NOT_REPEATED));
      paramView.fieldSetFunction(
          context
              .getDiscoGapicNamer()
              .getResourceSetterName(
                  param, SurfaceNamer.Cardinality.NOT_REPEATED, context.getNamer()));
      paramView.properties(Collections.emptyList());
      properties.add(paramView.build());
    }

    for (DiscoveryField entry : method.getInputFields()) {
      if (entry.mayBeInResourceName()) {
        requestView.hasRequiredProperties(true);
        continue;
      }
      String parameterName = entry.getNameAsParameter();
      properties.add(
          schemaToParamView(context, entry, parameterName, symbolTable, EscapeName.ESCAPE_NAME));
      if (entry.isRequired()) {
        requestView.hasRequiredProperties(true);
      }
    }

    StaticLangApiMessageView.Builder paramView = StaticLangApiMessageView.newBuilder();
    Method discoMethod = method.getDiscoMethod();
    String resourceName = DiscoGapicParser.getResourceIdentifier(discoMethod.path()).toLowerCamel();
    StringBuilder description =
        new StringBuilder(discoMethod.parameters().get(resourceName).description());
    description.append(String.format("\nIt must have the format `%s`. ", discoMethod.path()));
    description.append(String.format("\\`{%s}\\` must start with a letter,\n", resourceName));
    description.append(
        "and contain only letters (\\`[A-Za-z]\\`), numbers (\\`[0-9]\\`), dashes (\\`-\\`),\n"
            + "     * underscores (\\`_\\`), periods (\\`.\\`), tildes (\\`~\\`), plus (\\`+\\`) or percent\n"
            + "     * signs (\\`%\\`). It must be between 3 and 255 characters in length, and it\n"
            + "     * must not start with \\`\"goog\"\\`.");
    paramView.docLines(context.getNamer().getDocLines(description.toString()));
    paramView.rawName(resourceNameView.name());
    paramView.name(symbolTable.getNewSymbol(resourceNameView.name()));
    paramView.typeName("String");
    paramView.innerTypeName("String");
    paramView.isRequired(true);
    paramView.canRepeat(false);
    paramView.fieldGetFunction(resourceNameView.getCallName());
    paramView.fieldSetFunction(resourceNameView.setCallName());
    paramView.properties(new LinkedList<>());
    properties.add(paramView.build());

    requestView.hasFieldMask(method.hasExtraFieldMask());

    Collections.sort(properties);

    requestView.canRepeat(false);
    requestView.isRequired(true);
    requestView.properties(properties);

    Schema requestBodyDef = discoMethod.request();
    if (requestBodyDef != null && !Strings.isNullOrEmpty(requestBodyDef.reference())) {
      DiscoveryField requestBody =
          DiscoveryField.create(requestBodyDef, context.getDocContext().getApiModel());
      requestView.requestBodyType(
          schemaToParamView(
              context,
              requestBody,
              DiscoGapicParser.getMethodInputName(((DiscoveryMethodModel) method).getDiscoMethod())
                  .toLowerCamel(),
              symbolTable,
              EscapeName.NO_ESCAPE_NAME));
    }

    return requestView.build();
  }

  // Transforms a request/response Schema object into a StaticLangApiMessageView.
  private StaticLangApiMessageView schemaToParamView(
      SchemaTransformationContext context,
      DiscoveryField schema,
      String preferredName,
      SymbolTable symbolTable,
      EscapeName escapeName) {
    StaticLangApiMessageView.Builder paramView = StaticLangApiMessageView.newBuilder();
    String typeName = context.getSchemaTypeTable().getAndSaveNicknameFor(schema);
    String innerTypeName =
        context.getSchemaTypeTable().getAndSaveNicknameForElementType((FieldModel) schema);
    paramView.docLines(context.getNamer().getDocLines(schema.getDiscoveryField()));
    String name = context.getNamer().privateFieldName(Name.anyCamel(preferredName));
    String fieldName = name;
    if (escapeName.equals(EscapeName.ESCAPE_NAME)) {
      fieldName = symbolTable.getNewSymbol(name);
    }
    paramView.rawName(fieldName); // rawName doesn't matter for request objects.
    paramView.name(fieldName);
    paramView.typeName(typeName);
    paramView.innerTypeName(innerTypeName);
    paramView.isRequired(schema.isRequired());
    paramView.canRepeat(schema.isRepeated());
    paramView.fieldGetFunction(
        context.getDiscoGapicNamer().getResourceGetterName(name, context.getNamer()));
    paramView.fieldSetFunction(
        context
            .getDiscoGapicNamer()
            .getResourceSetterName(
                name,
                SurfaceNamer.Cardinality.ofRepeated(schema.isRepeated()),
                context.getNamer()));
    paramView.fieldAddFunction(context.getNamer().getFieldAddFunctionName(schema));
    paramView.properties(new LinkedList<>());
    return paramView.build();
  }

  private void addApiImports(ImportTypeTable typeTable) {
    typeTable.getAndSaveNicknameFor("com.google.api.core.BetaApi");
    typeTable.getAndSaveNicknameFor("com.google.common.collect.ImmutableList");
    typeTable.getAndSaveNicknameFor("com.google.common.collect.ImmutableMap");
    typeTable.getAndSaveNicknameFor("com.google.api.gax.httpjson.ApiMessage");
    typeTable.getAndSaveNicknameFor("com.google.gson.annotations.SerializedName");
    typeTable.getAndSaveNicknameFor("java.util.Collections");
    typeTable.getAndSaveNicknameFor("java.util.LinkedList");
    typeTable.getAndSaveNicknameFor("java.util.List");
    typeTable.getAndSaveNicknameFor("java.util.HashMap");
    typeTable.getAndSaveNicknameFor("java.util.Map");
    typeTable.getAndSaveNicknameFor("java.util.Objects");
    typeTable.getAndSaveNicknameFor("java.util.Set");
    typeTable.getAndSaveNicknameFor("javax.annotation.Generated");
    typeTable.getAndSaveNicknameFor("javax.annotation.Nullable");
  }

  private SchemaTypeTable createTypeTable(String implicitPackageName) {
    JavaTypeTable typeTable = new JavaTypeTable(implicitPackageName, IGNORE_JAVA_LANG_CLASH);
    SchemaTypeNameConverter typeNameConverter =
        new JavaSchemaTypeNameConverter(implicitPackageName, nameFormatter);
    return new SchemaTypeTable(
        typeTable,
        typeNameConverter,
        new JavaSurfaceNamer(implicitPackageName, implicitPackageName));
  }

  public enum EscapeName {
    ESCAPE_NAME,
    NO_ESCAPE_NAME
  }
}
