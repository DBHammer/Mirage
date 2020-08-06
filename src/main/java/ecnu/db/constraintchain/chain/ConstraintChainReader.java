package ecnu.db.constraintchain.chain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import ecnu.db.constraintchain.arithmetic.ArithmeticNode;
import ecnu.db.constraintchain.arithmetic.ArithmeticNodeDeserializer;
import ecnu.db.constraintchain.filter.BoolExprNode;
import ecnu.db.constraintchain.filter.BoolExprNodeDeserializer;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author wangqingshuai
 */
public class ConstraintChainReader {

    /**
     * todo 读取约束链的配置文件，返回读取到的约束链配置信息
     * 没有找到文件时抛出IO异常
     *
     * @return 加载成功的约束链
     */
    public static List<ConstraintChain> readConstraintChain(String fileName) throws IOException {
        String content = FileUtils.readFileToString(new File(fileName), UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(ConstraintChainNode.class, new ConstraintChainNodeDeserializer());
        module.addDeserializer(BoolExprNode.class, new BoolExprNodeDeserializer());
        module.addDeserializer(ArithmeticNode.class, new ArithmeticNodeDeserializer());
        mapper.registerModule(module);
        Map<String, List<ConstraintChain>> query2chain = mapper.readValue(content, new TypeReference<Map<String, List<ConstraintChain>>>() {});
        return query2chain.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
    }

}
