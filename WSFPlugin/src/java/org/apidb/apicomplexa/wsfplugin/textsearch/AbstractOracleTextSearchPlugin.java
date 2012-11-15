/**
 * KeywordSearchPlugin -- text search using Oracle Text
 */
package org.apidb.apicomplexa.wsfplugin.textsearch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.apidb.apicommon.controller.CommentActionUtility;
import org.apidb.apicommon.model.CommentFactory;
import org.gusdb.wdk.controller.CConstants;
import org.gusdb.wdk.model.WdkModelException;
import org.gusdb.wdk.model.dbms.DBPlatform;
import org.gusdb.wdk.model.dbms.SqlUtils;
import org.gusdb.wdk.model.jspwrap.WdkModelBean;
import org.gusdb.wsf.plugin.AbstractPlugin;
import org.gusdb.wsf.plugin.WsfRequest;
import org.gusdb.wsf.plugin.WsfResponse;
import org.gusdb.wsf.plugin.WsfServiceException;

/**
 * @author John I
 * @created Nov 16, 2008
 */
public abstract class AbstractOracleTextSearchPlugin extends AbstractPlugin {

    // required parameter definition
    public static final String PARAM_TEXT_EXPRESSION = "text_expression";
    public static final String PARAM_DATASETS = "text_fields";
    public static final String PARAM_WDK_RECORD_TYPE = "wdk_record_type";

    public static final String PARAM_DETAIL_TABLE = "detail_table";
    public static final String PARAM_PRIMARY_KEY_COLUMN = "primary_key_column";
    public static final String PARAM_PROJECT_ID = "project_id";

    public static final String COLUMN_RECORD_ID = "RecordID";
    public static final String COLUMN_PROJECT_ID = "ProjectId";
    public static final String COLUMN_DATASETS = "Datasets";
    public static final String COLUMN_MAX_SCORE = "MaxScore";

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.WsfPlugin#getColumns()
     */
    public String[] getColumns() {
        return new String[] { COLUMN_RECORD_ID, COLUMN_PROJECT_ID,
                COLUMN_DATASETS, COLUMN_MAX_SCORE };
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.WsfPlugin#getRequiredParameters()
     */
    public String[] getRequiredParameterNames() {
        return new String[] { PARAM_TEXT_EXPRESSION, PARAM_DATASETS };
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.gusdb.wsf.plugin.WsfPlugin#validateParameters(java.util.Map)
     */
    public void validateParameters(WsfRequest request)
            throws WsfServiceException {
        // do nothing in this plugin
    }


    protected String transformQueryString(String queryExpression) {

        // transform the user's search string onto an expression suitable for
	// passing to the Oracle Text CONTAINS() function: drop occurrences of AND and
	// OR, escape anything else with curly braces (to avert errors from the
	// database if any is on the long list of Oracle Text keywords), and (if it's
	// a multi-word phrase) use NEAR and ACCUM to give a higher score when all
	// terms are near each other
        // e.g. "calcium binding" becomes
	// "({calcium} NEAR {binding}) * 1.0 OR ({calcium} ACCUM {binding}) * 0.1"

        double nearWeight = 1;
        double accumWeight = .1;
        String transformed;

        String trimmed = queryExpression.trim().replaceAll("'", "").replaceAll("[-&|~,=;%_]", "\\\\$0");

        ArrayList<String> tokenized = tokenizer(trimmed);
        if (tokenized.size() > 1) {
            transformed = "(" + join(tokenized, " NEAR ") + ") * " + nearWeight
                    + " OR (" + join(tokenized, " ACCUM ") + ") * "
                    + accumWeight;
        } else if (tokenized.size() == 1) {
            transformed = tokenized.get(0);
	} else {
            transformed = wildcarded(trimmed);
        }

        return transformed;
    }

    private static String wildcarded(String queryExpression) {

	String wildcarded = queryExpression.replaceAll("\\*", "%");
	if (wildcarded.equals(queryExpression)) {
		// no wildcard
		return( "{" + queryExpression + "}" );
	    } else {
		return( wildcarded );
	    }

    }

    private static ArrayList<String> tokenizer(String input) {

        ArrayList<String> tokenized = new ArrayList<String>();

        boolean insideQuotes = false;
        for (String quoteChunk : input.split("\"")) {
            if (insideQuotes && quoteChunk.length() > 0) {
                tokenized.add(wildcarded(quoteChunk));
            } else {
                for (String spaceChunk : quoteChunk.split(" ")) {
                    if (spaceChunk.length() > 0 && !spaceChunk.toLowerCase().equals("and") && !spaceChunk.toLowerCase().equals("or")) {
                        tokenized.add(wildcarded(spaceChunk));
                    }
                }
            }
            insideQuotes = !insideQuotes;
        }

        return tokenized;
    }

    private static String join(ArrayList<String> parts, String delimiter) {
        boolean notFirstChunk = false;

        StringBuffer conjunction = new StringBuffer("");

        for (String part : parts) {
            if (notFirstChunk) {
                conjunction.append(delimiter);
            }

            conjunction.append(part);

            notFirstChunk = true;
        }

        return conjunction.toString();
    }


    //    private PreparedStatement getValidationQuery() throws WsfServiceException {
    //        String sql = new String("select attrs.source_id, attrs.project_id \n"
    //                + "from ApidbTuning.GeneId alias, ApidbTuning.GeneAttributes attrs \n"
    //                + "where alias.Id = ? \n"
    //                + "  and alias.gene = attrs.source_id \n"
    //                + "  and alias.unique_mapping = 1 \n"
    //                + "  and attrs.project_id = ? \n"
    //                + "  and ? like '%' || attrs.organism || '%'");
    //
    //        WdkModelBean wdkModel = (WdkModelBean) this.context
    //                .get(CConstants.WDK_MODEL_KEY);
    //        DBPlatform platform = wdkModel.getModel().getQueryPlatform();
    //        DataSource dataSource = platform.getDataSource();
    //
    //        try {
    //            return SqlUtils.getPreparedStatement(dataSource, sql);
    //        } catch (SQLException ex) {
    //            throw new WsfServiceException(ex);
    //        }
    //    }

    protected Map<String, SearchResult> textSearch(PreparedStatement query, String primaryKeyColumn)
            throws WsfServiceException, SQLException {
        Map<String, SearchResult> matches = new HashMap<String, SearchResult>();
        ResultSet rs = null;
        try {
            logger.info("about to execute text-search query (set org.gusdb logging to \"debug\" to see its text)");
            query.setFetchSize(100);
            rs = query.executeQuery();
            logger.info("finshed execute");
            while (rs.next()) {
                String sourceId = rs.getString(primaryKeyColumn);

                if (matches.containsKey(sourceId)) {
                    throw new WsfServiceException("duplicate sourceId "
                            + sourceId);
                } else {
                    SearchResult match = getSearchResults(rs, sourceId);
                    matches.put(sourceId, match);
                }
            }
            logger.info("finished fetching rows");
        } catch (SQLException ex) {
            logger.info("caught Exception " + ex.getMessage());
            ex.printStackTrace();
	    String message;
	    if (ex.getMessage().indexOf("DRG-51030") >= 0) {
		// DRG-51030: wildcard query expansion resulted in too many terms
		message = new String("Search term with wildcard (asterisk) characters matches too many keywords. Please include more non-wildcard characters.");
	    } else if (ex.getMessage().indexOf("ORA-01460") >= 0) {
		// ORA-01460: unimplemented or unreasonable conversion requested
                // it's unimplemented; it's unreasonable; it's outrageous, egregious, preposterous!
		message = new String("Search term is too long. Please try again with a shorter text term.");
	    } else {
		message = ex.getMessage();
	    }
	    throw new WsfServiceException(message, ex);
        } catch (Exception ex) {
            logger.info("caught Exception " + ex.getMessage());
            ex.printStackTrace();
            throw new WsfServiceException(ex);
        } finally {
            if (rs != null)
                rs.close();
        }

        return matches;
    }

    // requires two part key.  if only have one part, then query should return fake second part
    protected String[][] flattenMatches(SearchResult[] matches,
            String[] orderedColumns) throws WsfServiceException {

        // validate that WDK expects the columns in the order we want
        String[] expectedColumns = { "RecordID", "ProjectId", "MaxScore",
                "Datasets" };

        int i = 0;
        for (String expected : expectedColumns) {
            if (!expected.equals(orderedColumns[i])) {
                throw new WsfServiceException(
                        "misordered WSF column: expected \"" + expected
                                + "\", got \"" + orderedColumns[i]);
            }
            i++;
        }

        String[][] flat = new String[matches.length][];

        i = 0;
        for (SearchResult match : matches) {
            String[] a = { match.getSourceId(), match.getProjectId(),
                    Float.toString(match.getMaxScore()),
                    match.getFieldsMatched() };
            flat[i++] = a;
        }

        return flat;
    }

    protected SearchResult getSearchResults(ResultSet rs, String sourceId) throws SQLException {
	return new SearchResult(rs.getString("project_id"), sourceId,
				rs.getFloat("max_score"),
				rs.getString("fields_matched"));
    }

    protected Connection getDbConnection() throws SQLException {
        WdkModelBean wdkModel = (WdkModelBean) context
                .get(CConstants.WDK_MODEL_KEY);
        DBPlatform platform = wdkModel.getModel().getQueryPlatform();
        return platform.getDataSource().getConnection();
    }

    @Override
    protected String[] defineContextKeys() {
        return new String[] { CConstants.WDK_MODEL_KEY, CConstants.GUS_HOME_KEY };
    }

    protected SearchResult[] getMatchesSortedArray(Map<String, SearchResult> matches) {
        Collection<SearchResult> matchCollection = matches.values();
        SearchResult[] matchArray = matchCollection.toArray(new SearchResult[0]);
        Arrays.sort(matchArray);
	return matchArray;
    }
}
