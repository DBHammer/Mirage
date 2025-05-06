package ecnu.db.generator.constraintchain;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import ecnu.db.generator.constraintchain.agg.ConstraintChainAggregateNode;
import ecnu.db.generator.constraintchain.filter.BoolExprNode;
import ecnu.db.generator.constraintchain.filter.BoolExprNodeDeserializer;
import ecnu.db.generator.constraintchain.filter.ConstraintChainFilterNode;
import ecnu.db.generator.constraintchain.join.ConstraintChainFkJoinNode;
import ecnu.db.generator.constraintchain.join.ConstraintChainPkJoinNode;

import java.io.IOException;

public class ConstraintChainNodeDeserializer extends StdDeserializer<ConstraintChainNode> {

    public ConstraintChainNodeDeserializer() {
        this(null);
    }

    public ConstraintChainNodeDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public ConstraintChainNode deserialize(JsonParser parser, DeserializationContext deserializationContext) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(BoolExprNode.class, new BoolExprNodeDeserializer());
        mapper.registerModule(module);
        return switch (ConstraintChainNodeType.valueOf(node.get("constraintChainNodeType").asText())) {
            case FILTER -> mapper.readValue(node.toString(), ConstraintChainFilterNode.class);
            case FK_JOIN -> mapper.readValue(node.toString(), ConstraintChainFkJoinNode.class);
            case PK_JOIN -> mapper.readValue(node.toString(), ConstraintChainPkJoinNode.class);
            case AGGREGATE -> mapper.readValue(node.toString(), ConstraintChainAggregateNode.class);
        };
    }
}
