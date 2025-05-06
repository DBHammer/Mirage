package ecnu.db.generator.constraintchain.filter.arithmetic;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;

/**
 * @author alan
 */
public class ArithmeticNodeDeserializer extends StdDeserializer<ArithmeticNode> {

    public ArithmeticNodeDeserializer() {
        this(null);
    }

    public ArithmeticNodeDeserializer(Class<?> vc) {
        super(vc);
    }

    @Override
    public ArithmeticNode deserialize(JsonParser parser, DeserializationContext deserializationContext) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(ArithmeticNode.class, new ArithmeticNodeDeserializer());
        mapper.registerModule(module);
        return switch (ArithmeticNodeType.valueOf(node.get("type").asText())) {
            case CONSTANT -> mapper.readValue(node.toString(), NumericNode.class);
            case MINUS, PLUS, MUL, DIV, SUM, MIN, AVG, MAX -> mapper.readValue(node.toString(), MathNode.class);
            case COLUMN -> mapper.readValue(node.toString(), ColumnNode.class);
        };
    }
}
