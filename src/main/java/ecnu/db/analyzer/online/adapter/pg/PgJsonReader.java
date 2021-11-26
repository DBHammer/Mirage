package ecnu.db.analyzer.online.adapter.pg;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ReadContext;
import net.minidev.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PgJsonReader {
    private PgJsonReader() {
    }

    private static ReadContext readContext;


    static void setReadContext(String plan) {
        Configuration conf = Configuration.defaultConfiguration()
                .addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL)
                .addOptions(Option.SUPPRESS_EXCEPTIONS);
        PgJsonReader.readContext = JsonPath.using(conf).parse(plan);
    }

    static StringBuilder skipNodes(StringBuilder path) {
        while (new PgNodeTypeInfo().isPassNode(readNodeType(path))) {//找到第一个可以处理的节点
            path = move2LeftChild(path);
        }
        return path;
    }

    static List<String> readGroupKey(StringBuilder path) {
        return readContext.read(path + "['Group Key']");
    }


    static int readPlansCount(StringBuilder path) {
        if (readContext.read(path + "['Plans']") == null) {
            return 0;
        }
        return readContext.read(path + "['Plans'].length()");
    }

    static String readSubPlanIndex(StringBuilder path){
        String subPlanName = readContext.read(path + "['Subplan Name']");
        if(subPlanName!=null && subPlanName.contains("InitPlan")){
            Pattern returnRegex = Pattern.compile("returns \\$[0-9]+");
            Matcher matcher = returnRegex.matcher(subPlanName);
            if(matcher.find()){
                return matcher.group().replaceAll("returns ","");
            }
        }
        return null;
    }

    static String readSubPlanName(StringBuilder path){
        return readContext.read(path+"['Subplan Name']");
    }
    static boolean hasInitPlan(StringBuilder path) {
        List<String> subPlanTags = readContext.read(path + "['Plans'][*]['Subplan Name']");
        subPlanTags.removeAll(Collections.singleton(null));
        if (!subPlanTags.isEmpty()) {
            return subPlanTags.stream().anyMatch(subPlanTag -> subPlanTag.contains("InitPlan"));
        }
        return false;
    }

    static List<String> readOutput(StringBuilder path) {
        return readContext.read(path + "['Output']");
    }

    static int readActualLoops(StringBuilder path){
        return readContext.read(path + "['Actual Loops']");
    }

    static String readPlan(StringBuilder path, int index) {
        LinkedHashMap<String, Object> data = readContext.read(path + "['Plans'][" + index + "]");
        return JSONObject.toJSONString(data);
    }

    static String readFilterInfo(StringBuilder path) {
        return readContext.read(path + "['Filter']");
    }

    static String readJoinFilter(StringBuilder path) {
        return readContext.read(path + "['Join Filter']");
    }

    static  String readJoinCond(StringBuilder path) {
        return readContext.read(path + "['Hash Cond']");
    }
    static String readIndexJoin(StringBuilder path) {
        path = skipNodes(move2RightChild(path));
        //String indexCond =  "Index Cond: " + readContext.read(path + "['Index Cond']");
        String indexCond =  readContext.read(path + "['Index Cond']");
        if(indexCond == null){
            indexCond = "Recheck Cond: " + readContext.read(path + "['Recheck Cond']");
        }else{
            indexCond = "Index Cond: " + indexCond;
        }
        return indexCond;
    }

    static String readHashJoin(StringBuilder path) {
        StringBuilder joinInfo = new StringBuilder("Hash Cond: ").append((String) readContext.read(path + "['Hash Cond']"));
        String joinFilter;
        if ((joinFilter = readContext.read(path + "['Join Filter']")) != null) {
            joinInfo.append(" Join Filter: ").append(joinFilter);
        }
        return joinInfo.toString();
    }

    static String readMergeJoin(StringBuilder path) {
        StringBuilder joinInfo = new StringBuilder("Merge Cond: ").append((String) readContext.read(path + "['Merge Cond']"));
        String joinFilter;
        if ((joinFilter = readContext.read(path + "['Join Filter']")) != null) {
            joinInfo.append(" Join Filter: ").append(joinFilter);
        }
        return joinInfo.toString();
    }

    static int readRowsRemoved(StringBuilder path) {
        return readContext.read(path + "['Rows Removed by Filter']");
    }

    static int readRowsRemovedByJoinFilter(StringBuilder path) {
        return readContext.read(path + "['Rows Removed by Join Filter']");
    }

    static StringBuilder move2LeftChild(StringBuilder path) {
        return new StringBuilder(path).append("['Plans'][0]");
    }

    static StringBuilder move2RightChild(StringBuilder path) {
        return new StringBuilder(path).append("['Plans'][1]");
    }

    static StringBuilder move3ThirdChild(StringBuilder path) { return new StringBuilder(path).append("['Plans'][2]"); }

    static int readRowCount(StringBuilder path) {
        return readContext.read(path + "['Actual Rows']");
    }

    static String readNodeType(StringBuilder path) {
        return readContext.read(path + "['Node Type']");
    }

    static String readTableName(String path) {
        return readContext.read(path + "['Schema']") + "." + readContext.read(path + "['Relation Name']");
    }

    static String readAlias(String path) {
        return readContext.read(path + "['Alias']");
    }
}