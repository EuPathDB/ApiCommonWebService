/**
 * Version 2.0.0 -- Updated to work with the new Wdk Model. The loading subroutine was updated to call parse()
 * correctly for the new code in teh WDK 2/27/2008 -- Removed valiadtion of the columns and inserted code to
 * insert "N/A" into the result is a column does not exists on a component site 6/2/2010 -- if a querySet such
 * as SharedVQ is received, and not found, the site will check sharedParams paramSet. This will allow to
 * access enums from a flatvocab in portal (only way to access a site) --cris
 */
package org.apidb.apicomplexa.wsfplugin.wdkquery;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.gusdb.fgputil.FormatUtil;
import org.gusdb.fgputil.runtime.InstanceManager;
import org.gusdb.fgputil.validation.ValidationLevel;
import org.gusdb.oauth2.client.ValidatedToken;
import org.gusdb.wdk.model.Utilities;
import org.gusdb.wdk.model.WdkModel;
import org.gusdb.wdk.model.WdkModelException;
import org.gusdb.wdk.model.dbms.ResultList;
import org.gusdb.wdk.model.query.Query;
import org.gusdb.wdk.model.query.QueryInstance;
import org.gusdb.wdk.model.query.param.AbstractEnumParam;
import org.gusdb.wdk.model.query.param.EnumParamVocabInstance;
import org.gusdb.wdk.model.query.param.FilterParamNew;
import org.gusdb.wdk.model.query.param.FlatVocabParam;
import org.gusdb.wdk.model.query.param.Param;
import org.gusdb.wdk.model.query.param.StringParam;
import org.gusdb.wdk.model.query.spec.ParameterContainerInstanceSpecBuilder.FillStrategy;
import org.gusdb.wdk.model.query.spec.QueryInstanceSpec;
import org.gusdb.wdk.model.question.Question;
import org.gusdb.wdk.model.user.StepContainer;
import org.gusdb.wdk.model.user.User;
import org.gusdb.wdk.model.user.UserFactory;
import org.gusdb.wsf.plugin.AbstractPlugin;
import org.gusdb.wsf.plugin.PluginModelException;
import org.gusdb.wsf.plugin.PluginRequest;
import org.gusdb.wsf.plugin.PluginResponse;
import org.gusdb.wsf.plugin.PluginUserException;

/**
 * @author Cary Pennington
 * @created Dec 20, 2006
 * 
 *          2.0.0 -- Worked with ApiFedPlugin 2.0.0 2.1 -- Ditched the three number versioning... not that
 *          many changes -- Added support for accessing Enum Parameters on the componet Sites
 */
public class WdkQueryPlugin extends AbstractPlugin {

  private static final Logger logger = Logger.getLogger(WdkQueryPlugin.class);

  // Propert values

  public static final String VERSION = "3.0";
  // Input Parameters
  public static final String PARAM_PARAMETERS = "Parameters";
  public static final String PARAM_COLUMNS = "Columns";

  public static final int STATUS_ERROR_NO_QUERY = -2;
  public static final int STATUS_ERROR_MODEL_EXCEPTION = -1;
  public static final int STATUS_ERROR_SERVICE_UNAVAILABLE = -3;

  // Member Variables
  private WdkModel wdkModel;

  public WdkQueryPlugin() {
    super();
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.WsfPlugin#getRequiredParameters()
   */
  @Override
  public String[] getRequiredParameterNames() {
    return new String[] {};
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.WsfPlugin#getColumns()
   */
  @Override
  public String[] getColumns(PluginRequest request) {
    return new String[] {};
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.plugin.WsfPlugin#validateParameters(java.util.Map)
   */
  @Override
  public void validateParameters(PluginRequest request) {
    // do nothing in this plugin
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.gusdb.wsf.WsfPlugin#execute(java.util.Map, java.lang.String[])
   */
  @Override
  public int execute(PluginRequest request, PluginResponse response) throws PluginModelException, PluginUserException {

    logger.info("WdkQueryPlugin Version : " + WdkQueryPlugin.VERSION);

    // logger.info("Invoking WdkQueryPlugin......");
    int resultSize = 0;
    Map<String, String> paramValues = request.getParams();
    Map<String, String> context = request.getContext();

    // when running a param search, the question should hold the question
    // that has the query to which the param belongs; the param should hold
    // the flatVocab param name; the query is the name of the vocab query.

    // when running a id search, the question holds the name of the search,
    // the param should be empty, and the query holds the name of the id
    // query.
    String questionFullName = context.get(Utilities.CONTEXT_KEY_QUESTION_FULL_NAME);
    String paramName = context.get(Utilities.CONTEXT_KEY_PARAM_NAME);
    String queryName = context.get(Utilities.CONTEXT_KEY_QUERY_FULL_NAME);

    if (paramValues.containsKey("Query"))
      paramValues.remove("Query");
    // logger.info("QueryName = "+ invokeKey);

    // Map<String,Object>SOParams = convertParams(params);
    // logger.info("Parameters were processed");

    try {
      logger.info("context question: '" + questionFullName + "', param: '" + paramName + "', query: '" +
          queryName + "', SiteModel=" + request.getProjectId());

      // Variable to maintain the order of columns in the result... maintains
      // order given by Federation Plugin
      String[] orderedColumns = request.getOrderedColumns();
      Map<String, Integer> columnOrders = new LinkedHashMap<>(orderedColumns.length);
      for (int i = 0; i < orderedColumns.length; i++) {
        columnOrders.put(orderedColumns[i], i);
      }

      wdkModel = InstanceManager.getInstance(WdkModel.class, request.getProjectId());

      // This wait ensures the new guest user_id generated by the portal, gets replicated in the component site user database.
      // This wait is needed when the comp site's user db is in one campus (N/S) and the portal's user db is is the other one.
      // 
      // Upon the portal home page reload, the client(browser) first runs an important service call (/service/) that returns basic information.
      // Without this new 2sec wait, when ALL component sites are on a different campus than the portal, the portal service breaks (500).
      // If this important service breaks, the whole site becomes disabled. 
      //
      // The specific part of the service that breaks under these conditions is the organims/project mapping.
      // - the mapping is generated by getting from the comp sites the organisms in the organism parameter in GenomicSequenceQuestions.SequencesByTaxon
      // - if no component returns any organism because the guest id was not found -because it was not replicated quickly enough- the portal throws an error, which turns into a service 500 error.
      // 
      // With this wait we ensure the component site will find the user-id and will be able to return the organisms to the portal
      try
      {
        Thread.sleep(2000);
      }
      catch(InterruptedException ex)
      {
        Thread.currentThread().interrupt();
      }

      // FIXME: should not go to OAuth to find user again; find a way to avoid this
      String bearerToken = request.getContext().get(Utilities.CONTEXT_KEY_VALIDATED_TOKEN_OBJECT);
      UserFactory factory = wdkModel.getUserFactory();
      ValidatedToken token = factory.validateBearerToken(bearerToken);
      User user = factory.convertToUser(token);

      // web service call to get param values
      if (paramName != null) {
        resultSize = writeParamResult(response, user, paramValues, columnOrders, questionFullName, paramName);
        logger.info("Param results have been processed.... " + resultSize);
        return resultSize;
      }

      // check if question is set
      Query query;
      if (questionFullName != null) {
        query = wdkModel.getQuestionByFullName(questionFullName)
            .orElseThrow(() -> new PluginModelException("Cannot find question with passed context name " + questionFullName))
            .getQuery();
      }
      else {
        query = (Query) wdkModel.resolveReference(queryName);
      }

      // if query has answer params, throw; they are not allowed
      if (query.getAnswerParamCount() > 0) {
        throw new PluginUserException("This operation cannot be performed on combiner queries such as " + query.getFullName());
      }

      // converting from internal values to dependent values
      Map<String, String> SOParams = convertParams(user, paramValues, query.getParamMap());// getParamsFromQuery(q));

      // execute query, and get results back
      logger.info("Processing Query " + query.getFullName() + "...");
      logger.info("Params used to create query instance: " + FormatUtil.prettyPrint(SOParams));
      
      QueryInstanceSpec qiSpec = QueryInstanceSpec
          .builder()
          .putAll(SOParams)
          .buildValidated(user, query, StepContainer.emptyContainer(), ValidationLevel.RUNNABLE, FillStrategy.NO_FILL);
      if (qiSpec.isRunnable()) {
        QueryInstance<?> queryInstance = Query.makeQueryInstance(qiSpec.getRunnable().getLeft());
        try (ResultList resultList = queryInstance.getResults()) {
          logger.info("Results set was filled");
          resultSize = writeQueryResults(response, resultList, columnOrders);
          logger.info("Query results have been processed.... " + resultSize);
        }
      }
      else {
        String joinedValidationErrors = FormatUtil.join(qiSpec.getValidationBundle().getAllErrors(), FormatUtil.NL);
        // NOTE: used to catch a WdkUserException here, but now reading validation errors
        // FIXME: The new messages MAY NOT MATCH the old ones, so we may be returning the wrong thing here
        //logger.info("WdkUserException in execute()" + ex.toString());
        String msg = joinedValidationErrors; // ex.toString();
        logger.info("Message = " + msg);
        if (msg.contains("Please choose value(s) for parameter")) {
          resultSize = 0;
        }
        else if (msg.contains("No value supplied for param")) {
          resultSize = 0;
          // isolate query on crypto/plasmo with only param values for plasmo
        }
        else if (msg.contains("does not exist")) {
          resultSize = 0;
        }
        else if (msg.contains("does not contain")) {
          resultSize = -2; // query set or query doesn't exist
          // } else if (msg.indexOf("encountered an invalid term") != -1) {
          // resultSize = 0; // parameter value relates to a different comp site
        }
        else if (msg.contains("does not include")) {
          resultSize = -2; // query set or query doesn't exist
        }
        else if (msg.contains("datasets value '' has an error: Missing the value")) {
          resultSize = 0;
        }
        else if (msg.contains("Invalid term")) {
          resultSize = 0;
        }
        else if (msg.contains("Some of the input parameters are invalid")) {
          resultSize = 0;
        }
        else {
          logger.error("Unrecognized validation error: " + msg);
          resultSize = -1; // actual error, can't handle
        }
      }
    }
    catch (Exception ex) {
      logger.error("OTHER exception IN execute()", ex);

      resultSize = -1;
    }

    response.setMessage(Integer.toString(resultSize));
    return resultSize;
  }

  private int writeParamResult(PluginResponse response, User user, Map<String, String> paramValues,
      Map<String, Integer> columnOrders, String questionFullName, String paramName) throws PluginModelException, WdkModelException, PluginUserException {
    // get param
    Param param;
    if (questionFullName != null) {
      // context question is defined, should get the param from question
      Question question = wdkModel.getQuestionByFullName(questionFullName)
          .orElseThrow(() -> new PluginModelException("Cannot find question with passed context name " + questionFullName));
      String partName = paramName.substring(paramName.indexOf(".") + 1);
      param = question.getParamMap().get(partName);

      logger.debug("got param from question: " + (param != null));

      // param doesn't exist in the context question, try to get
      // it from model.
      if (param == null)
        // param = (Param) wdkModel.resolveReference(paramName);
        throw new PluginModelException("parameter " + paramName + " does not exist in question " +
            questionFullName);
    }
    else {
      logger.debug("got param from model.");

      // context question is not defined, get original param from
      // model.
      param = (Param) wdkModel.resolveReference(paramName);
    }
    logger.debug("Parameter found : " + param.getFullName());
    if (param instanceof FlatVocabParam)
      logger.debug("param query: " + ((FlatVocabParam) param).getVocabularyQuery().getFullName());

    // only process the result if it's an enum param
    if (param instanceof AbstractEnumParam) {
      return handleVocabParams(response, user, (AbstractEnumParam) param, paramValues, columnOrders);
    }
    else
      return 0;
  }

  private int writeQueryResults(PluginResponse response, ResultList results, Map<String, Integer> columnOrders)
      throws PluginModelException, PluginUserException, WdkModelException {
    int resultSize = 0;
    while (results.next()) {
      String[] row = new String[columnOrders.size()];
      for (String column : columnOrders.keySet()) {
        // read the value for the column, and get the string representation
        Object obj = results.get(column);
        String val = null;
        if (obj == null)
          val = ""; // use empty instead of N/A
        else if (obj instanceof String)
          val = (String) obj;
        else if (obj instanceof char[])
          val = new String((char[]) obj);
        else if (obj instanceof byte[])
          val = new String((byte[]) obj);
        else
          val = obj.toString();

        // assign the column to the proper place
        row[columnOrders.get(column)] = val;
      }

      // save row to repsonse
      response.addRow(row);
      resultSize++;
    }
    results.close();
    return resultSize;
  }

  private Map<String, String> convertParams(User user, Map<String, String> paramValues,
      Map<String, Param> params) throws WdkModelException {
    Map<String, String> ret = new HashMap<String, String>();
    for (String key : paramValues.keySet()) {
      String value = paramValues.get(key);
      if (params.containsKey(key)) {
        Param param = params.get(key);
        if (param instanceof FilterParamNew) {
          // do nothing, will use the value as is.
        }
        else if (param instanceof AbstractEnumParam) {
          String valList = value;
          AbstractEnumParam abParam = (AbstractEnumParam) param;
          EnumParamVocabInstance vocabInstance = abParam.getVocabInstance(user, paramValues);
          if ((param instanceof FlatVocabParam || param.isAllowEmpty()) && valList.length() == 0) {
            valList = abParam.getDefault(null, vocabInstance);
          }

          // Code to specifically work around a specific problem
          // created by the OrthologPattern Question
          if (param.getName().equalsIgnoreCase("phyletic_indent_map"))
            valList = "ARCH";
          if (param.getName().equalsIgnoreCase("phyletic_term_map"))
            valList = "rnor";
          // end workaround

          String[] vals = valList.split(",");
          String newVals = "";
          for (String mystring : vals) {
            // unescape each individual term.
            mystring = unescapeValue(mystring, abParam.getQuote());
            try {
              logger.debug("ParamName = " + param.getName() + " ------ Value = " + mystring);
              if (validateSingleValues(vocabInstance, mystring.trim())) {
                // ret.put(param.getName(), o);
                newVals = newVals + "," + mystring.trim();
                logger.debug("validated-------------\n ParamName = " + param.getName() + " ------ Value = " +
                    mystring);
              }
              else {
                logger.warn("param validation failed: " + "param=" + param.getName() + ", value='" +
                    mystring + "'");
              }
            }
            catch (Exception e) {
              logger.error("error occurred.", e);
            }
          }

          if (newVals.length() != 0)
            newVals = newVals.substring(1);
          else
            newVals = "\u0000";
          logger.debug("validated values string -------------" + newVals);
          value = newVals;
        }
        else { // other types, unescape the whole thing
          boolean quoted = true;
          if (param instanceof StringParam)
            quoted = !((StringParam) param).isNumber();
          value = unescapeValue(value, quoted);
        }
        ret.put(param.getName(), value);
      }
    }
    return ret;
  }

  private boolean validateSingleValues(EnumParamVocabInstance vocab, String value) {
    String[] conVocab = vocab.getVocab();
    logger.debug("conVocab.length = " + conVocab.length);
    for (String v : conVocab) {
      if (value.equalsIgnoreCase(v))
        return true;
    }
    return false;
  }

  private int handleVocabParams(PluginResponse response, User user, AbstractEnumParam vocabParam,
      Map<String, String> ps, Map<String, Integer> columnOrders) throws PluginModelException,
      PluginUserException, WdkModelException {
    logger.debug("Function to Handle a vocab param in WdkQueryPlugin: " + vocabParam.getFullName());

    EnumParamVocabInstance vocabInstance = vocabParam.getVocabInstance(user, ps);
    Map<String, String> displayMap = vocabInstance.getDisplayMap();
    Map<String, String> parentMap = vocabInstance.getParentMap();

    boolean hasTerm = false, hasInternal = false;
    int count = 0;
    for (String term : displayMap.keySet()) {
      String[] row = new String[columnOrders.size()];
      for (String column : columnOrders.keySet()) {
        int index = columnOrders.get(column);
        if (column.equals(FlatVocabParam.COLUMN_TERM)) {
          row[index] = term;
          hasTerm = true;
        }
        else if (column.equals(FlatVocabParam.COLUMN_DISPLAY)) {
          row[index] = displayMap.get(term);
        }
        else if (column.equals(FlatVocabParam.COLUMN_INTERNAL)) {
          // always return term as internal in this plugin.
          row[index] = term;
          hasInternal = true;
        }
        else if (column.equals(FlatVocabParam.COLUMN_PARENT_TERM)) {
          row[index] = parentMap.get(term);
        }
        else {
          throw new PluginModelException("Unsupported column: " + column);
        }
      }
      response.addRow(row);
      count++;
    }

    // term & internal has to exist
    if (!hasTerm || !hasInternal)
      throw new WdkModelException("The wsf call for param " + vocabParam.getFullName() +
          " doesn't specify term & internal columns.");
    return count;
  }

  private String unescapeValue(String value, boolean quoted) {
    if (value == null || value.length() == 0)
      return value;

    // will first remove the wrapping quote
    if (quoted) {
      if (value.charAt(0) == '\'')
        value = value.substring(1);
      if (value.charAt(value.length() - 1) == '\'')
        value = value.substring(0, value.length() - 1);
    }

    // then replace double single-quotes to a single quote
    value = value.replace("''", "'");

    return value;
  }
}
