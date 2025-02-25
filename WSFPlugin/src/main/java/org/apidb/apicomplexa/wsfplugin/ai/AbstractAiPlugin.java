package org.eupathdb.websvccommon.wsfplugin.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.apache.log4j.Logger;
import org.gusdb.wsf.plugin.AbstractPlugin;
import org.gusdb.wsf.plugin.PluginModelException;
import org.gusdb.wsf.plugin.PluginRequest;
import org.gusdb.wsf.plugin.PluginResponse;
import org.gusdb.wsf.plugin.PluginUserException;
import org.json.JSONObject;

import java.util.Optional;

/**
 * Abstract superclass for AI-based WSF plugins, providing OpenAI API integration.
 */
public abstract class AbstractAiPlugin extends AbstractPlugin {

  private static final Logger LOG = Logger.getLogger(AbstractAiPlugin.class);
  private static final String API_KEY_ENV_VAR = "OPENAI_API_KEY";

  protected final OpenAIClient openAiClient;

  public AbstractAiPlugin() {
    super();
    this.openAiClient = initializeOpenAiClient();
  }

  private OpenAIClient initializeOpenAiClient() {
    String apiKey = Optional.ofNullable(System.getenv(API_KEY_ENV_VAR))
        .orElseThrow(() -> new IllegalStateException("Missing OpenAI API key. Set " + API_KEY_ENV_VAR + " in the environment."));
    LOG.info("Initialized OpenAI API client with key from environment");
    return OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .build();
  }

  /**
   * Subclasses must implement this method to make a request to OpenAI and return the response.
   * @param request The WSF plugin request.
   * @return JSON-friendly object representing OpenAI's response.
   * @throws PluginModelException on processing failure.
   * @throws PluginUserException on user input errors.
   */
  protected abstract JSONObject callOpenAiApi(PluginRequest request) throws PluginModelException, PluginUserException;

  @Override
  protected int execute(PluginRequest request, PluginResponse response) throws PluginModelException, PluginUserException {
    try {
      LOG.info("Processing AI request with parameters: " + request.getParams());
      
      JSONObject resultJson = callOpenAiApi(request);
      
      response.addRow(new String[]{ resultJson.toString() });
      return 0;
    } catch (Exception e) {
      LOG.error("Error processing AI request", e);
      throw new PluginModelException("Error calling OpenAI API", e);
    }
  }
}

