package ecnu.db.generator.constraintchain;

import com.fasterxml.jackson.core.type.TypeReference;
import ecnu.db.LanguageManager;
import ecnu.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ecnu.db.utils.CommonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static ecnu.db.utils.CommonUtils.readFile;

public class ConstraintChainManager {
    public static final String CONSTRAINT_CHAINS_INFO = "/constraintChain.json";
    private static final Logger logger = LoggerFactory.getLogger(ConstraintChainManager.class);
    private static final ConstraintChainManager INSTANCE = new ConstraintChainManager();
    private static final String[] COLOR_LIST = {"#FFFFCC", "#CCFFFF", "#FFCCCC"};
    private static final String GRAPH_TEMPLATE = "digraph \"%s\" {rankdir=BT;" + System.lineSeparator() + "%s}";
    private String resultDir;
    private final ResourceBundle rb = LanguageManager.getInstance().getRb();
    private static final String WORKLOAD_DIR = "/workload";

    private boolean isDraw = false;

    private ConstraintChainManager() {
    }

    public static ConstraintChainManager getInstance() {
        return INSTANCE;
    }


    public void setResultDir(String resultDir) {
        this.resultDir = resultDir;
    }

    public static Map<String, List<ConstraintChain>> loadConstrainChainResult(String resultDir) throws IOException {
        String path = resultDir + WORKLOAD_DIR;
        File sqlDic = new File(path);
        File[] sqlArray = sqlDic.listFiles();
        assert sqlArray != null;
        Map<String, List<ConstraintChain>> result = new HashMap<>();
        for (File file : sqlArray) {
            if (!file.isDirectory()) {
                continue;
            }
            File[] graphArray = file.listFiles();
            assert graphArray != null;
            for (File file1 : graphArray) {
                if (file1.getName().contains("json")) {
                    Map<String, List<ConstraintChain>> eachresult = CommonUtils.MAPPER.readValue(readFile(file1.getPath()), new TypeReference<>() {
                    });
                    result.putAll(eachresult);
                }
            }
        }
        return result;
    }

    /**
     * 清理不影响键值填充的约束链
     *
     * @param query2chains query和约束链的map
     */
    public void cleanConstrainChains(Map<String, List<ConstraintChain>> query2chains) {
        for (var query2ConstraintChains : query2chains.entrySet()) {
            Iterator<ConstraintChain> constraintChainIterator = query2ConstraintChains.getValue().iterator();
            while (constraintChainIterator.hasNext()) {
                ConstraintChain constraintChain = constraintChainIterator.next();
                if (constraintChain.getNodes().stream().allMatch(
                        node -> node.getConstraintChainNodeType() == ConstraintChainNodeType.FILTER ||
                                (node.getConstraintChainNodeType() == ConstraintChainNodeType.AGGREGATE &&
                                        ((ConstraintChainAggregateNode) node).getGroupKey() == null))) {
                    logger.info(rb.getString("RemoveConstraintChain1"), query2ConstraintChains.getKey(), constraintChain);
                    constraintChainIterator.remove();
                }
            }
        }
    }

    public void storeConstraintChain(Map<String, List<ConstraintChain>> query2constraintChains) throws IOException {
        File workLoadDic = new File(resultDir + WORKLOAD_DIR);
        if (!workLoadDic.exists()) {
            workLoadDic.mkdir();
        }
        for (Map.Entry<String, List<ConstraintChain>> entry : query2constraintChains.entrySet()) {
            String constraintChainsContent = CommonUtils.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entry);
            File sqlDic = new File(resultDir + WORKLOAD_DIR + "/" + entry.getKey().split("\\.")[0]);
            if (!sqlDic.exists()) {
                sqlDic.mkdir();
            }
            CommonUtils.writeFile(resultDir + WORKLOAD_DIR + "/" + entry.getKey().split("\\.")[0] + "/" + entry.getKey() + ".json", constraintChainsContent);
        }
        for (Map.Entry<String, List<ConstraintChain>> stringListEntry : query2constraintChains.entrySet()) {
            String path = resultDir + WORKLOAD_DIR + "/" + stringListEntry.getKey().split("\\.")[0] + "/" + stringListEntry.getKey() + ".dot";
            File file = new File(resultDir + WORKLOAD_DIR + "/" + stringListEntry.getKey().split("\\.")[0]);
            File[] array = file.listFiles();
            assert array != null;
            String graphName = stringListEntry.getKey() + ".dot";
            boolean graphNotExist = Arrays.stream(array).map(File::getName).noneMatch(fileName -> fileName.equals(graphName));
            if (graphNotExist) {
                String graph = presentConstraintChains(stringListEntry.getKey(), stringListEntry.getValue());
                CommonUtils.writeFile(path, graph);
            } else {
                String oldGraph = Files.readString(Paths.get(path));
                String graph = presentConstraintChains(stringListEntry.getKey(), stringListEntry.getValue());
                if (removeData(graph).equals(removeData(oldGraph))) {
                    CommonUtils.writeFile(path, graph);
                } else {
                    Calendar date = Calendar.getInstance();
                    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
                    String currentTime = format.format(date.getTime());
                    String newPath = resultDir + WORKLOAD_DIR + "/" + stringListEntry.getKey().split("\\.")[0] + "/" + currentTime + stringListEntry.getKey() + ".dot";
                    CommonUtils.writeFile(newPath, graph);
                    logger.warn("graph {} is different", stringListEntry.getKey());
                }
            }
        }
    }

    public boolean isDraw() {
        return isDraw;
    }

    private String presentConstraintChains(String queryName, List<ConstraintChain> constraintChains) {
        StringBuilder graph = new StringBuilder();
        HashMap<String, ConstraintChain.SubGraph> subGraphHashMap = HashMap.newHashMap(constraintChains.size());
        constraintChains.sort(Comparator.comparing(ConstraintChain::getTableName));
        isDraw = true;
        for (int i = 0; i < constraintChains.size(); i++) {
            graph.append(constraintChains.get(i).presentConstraintChains(subGraphHashMap, COLOR_LIST[i % COLOR_LIST.length]));
        }
        String subGraphs = subGraphHashMap.values().stream().
                map(ConstraintChain.SubGraph::toString).sorted().collect(Collectors.joining(""));
        isDraw = false;
        return String.format(GRAPH_TEMPLATE, queryName, subGraphs + graph);
    }


    private String removeData(String graph) {
        String newGraph = graph.replaceAll("\\{id:\\d+, data:[^}]+", "");
        newGraph = newGraph.replaceAll("key\\d+", "key");
        newGraph = newGraph.replaceAll("color=\"#[F|C]+\"", "color");
        return newGraph;
    }
}
