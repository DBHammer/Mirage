package ecnu.db.generator.constraintchain.filter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import ecnu.db.generator.constraintchain.filter.operation.AbstractFilterOperation;
import ecnu.db.generator.constraintchain.filter.operation.UniVarFilterOperation;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static ecnu.db.generator.constraintchain.filter.BoolExprType.*;

public class LogicNode extends BoolExprNode {
    private BoolExprType type;
    private List<BoolExprNode> children;

    public List<BoolExprNode> getChildren() {
        return children;
    }

    public void setChildren(List<BoolExprNode> children) {
        this.children = children;
    }

    @Override
    public boolean hasKeyColumn() {
        return children.stream().anyMatch(BoolExprNode::hasKeyColumn);
    }


    @Override
    public List<AbstractFilterOperation> pushDownProbability(BigDecimal probability) {
        List<List<AbstractFilterOperation>> operationsOfTrees = new ArrayList<>();
        // 如果当前节点为 OR 且 概率不为 1， 则反转该树为AND
        boolean hasReverse = false;
        if (getRealType() == OR) {
            this.reverse();
            hasReverse = true;
            probability = BigDecimal.ONE.subtract(probability);
        }

        boolean allTrue = true;
        List<Integer> onlyNoEqlOperations = new ArrayList<>();
        for (int index = 0; index < children.size(); index++) {
            BoolExprNode child = children.get(index);
            if (child.isTrue()) {
                List<AbstractFilterOperation> subOperations = child.pushDownProbability(BigDecimal.ONE);
                if (subOperations.stream().noneMatch(operation -> operation.getOperator().isEqual())) {
                    onlyNoEqlOperations.add(index);
                }
                operationsOfTrees.add(subOperations);
            } else {
                allTrue = false;
                operationsOfTrees.add(child.pushDownProbability(probability));
            }
        }
        if (allTrue && probability.compareTo(BigDecimal.ONE) < 0) {
            int index = onlyNoEqlOperations.isEmpty() ? 0 : onlyNoEqlOperations.remove(0);
            operationsOfTrees.set(index, children.get(index).pushDownProbability(probability));
        }
        if (hasReverse) {
            this.reverse();
        }
        return operationsOfTrees.stream().flatMap(Collection::stream).toList();
    }

    @Override
    public void getColumn2ParameterBucket(Map<String, Map<String, List<Integer>>> column2Value2ParameterList) {
        if (children.size() == 1 && children.get(0) instanceof UniVarFilterOperation uniVarFilterOperation) {
            uniVarFilterOperation.getColumn2ParameterBucket(column2Value2ParameterList);
        }
    }


    public void removeOtherTablesOperation(String tableName) {
        BoolExprNode root = this.children.get(0);
        if (root.getType() == AND) {
            LogicNode andRoot = (LogicNode) root;
            List<BoolExprNode> prepareForRemove = andRoot.getChildren().stream()
                    .filter(child -> child.isDifferentTable(tableName)).toList();
            if (prepareForRemove.size() > 1) {
                throw new UnsupportedOperationException();
            }
            andRoot.getChildren().removeAll(prepareForRemove);
        }
    }

    @Override
    public BoolExprType getType() {
        return type;
    }

    public void setType(BoolExprType type) {
        this.type = type;
    }

    @Override
    public boolean[] evaluate() {
        boolean[][] computeVectors = new boolean[children.size()][];
        for (int i = 0; i < children.size(); i++) {
            computeVectors[i] = children.get(i).evaluate();
        }
        boolean[] resultVector = computeVectors[0];
        if (getRealType() == AND) {
            for (int i = 0; i < resultVector.length; i++) {
                for (int j = 1; j < computeVectors.length; j++) {
                    resultVector[i] &= computeVectors[j][i];
                }
            }
        } else if (getRealType() == OR) {
            for (int i = 0; i < resultVector.length; i++) {
                for (int j = 1; j < computeVectors.length; j++) {
                    resultVector[i] |= computeVectors[j][i];
                }
            }
        }
        return resultVector;
    }

    @JsonIgnore
    @Override
    public List<Parameter> getParameters() {
        return children.stream().map(BoolExprNode::getParameters).flatMap(Collection::stream).toList();
    }

    @JsonIgnore
    @Override
    public List<String> getColumns() {
        return children.stream().map(BoolExprNode::getColumns).flatMap(Collection::stream).toList();
    }

    @Override
    public BigDecimal getNullProbability() {
        BigDecimal maxNull = BigDecimal.ZERO;
        if (type == AND) {
            for (BoolExprNode child : children) {
                maxNull = maxNull.max(child.getNullProbability());
            }
        } else if (type == OR) {
            for (BoolExprNode child : children) {
                if (child.getFilterProbability().compareTo(BigDecimal.ZERO) > 0) {
                    maxNull = maxNull.max(child.getNullProbability());
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }
        return maxNull;
    }

    @Override
    public BigDecimal getFilterProbability() {
        if (type == AND) {
            BigDecimal minProbability = BigDecimal.ONE;
            for (BoolExprNode child : children) {
                minProbability = minProbability.min(child.getFilterProbability());
            }
            return minProbability;
        } else if (type == OR) {
            BigDecimal maxProbability = BigDecimal.ZERO;
            for (BoolExprNode child : children) {
                maxProbability = maxProbability.max(child.getFilterProbability());
            }
            return maxProbability;
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public void reverse() {
        if (type == AND || type == OR) {
            isReverse = !isReverse;
        } else {
            throw new UnsupportedOperationException();
        }
        for (BoolExprNode child : children) {
            child.reverse();
        }
    }

    @Override
    public boolean isTrue() {
        return switch (getRealType()) {
            case AND -> children.stream().allMatch(BoolExprNode::isTrue);
            case OR -> children.stream().anyMatch(BoolExprNode::isTrue);
            default -> throw new UnsupportedOperationException();
        };
    }

    @Override
    public boolean isDifferentTable(String tableName) {
        return children.stream().anyMatch(child -> child.isDifferentTable(tableName));
    }

    private BoolExprType getRealType() {
        if (isReverse) {
            return switch (type) {
                case AND -> OR;
                case OR -> AND;
                default -> throw new UnsupportedOperationException();
            };
        } else {
            return type;
        }
    }


    @Override
    public String toString() {
        String lowerType = switch (type) {
            case AND -> "and";
            case OR -> "or";
            default -> throw new UnsupportedOperationException();
        };
        if (children.size() > 1) {
            return "(" + children.stream().map(BoolExprNode::toString).collect(Collectors.joining(" " + lowerType + System.lineSeparator())) + ")";
        } else {
            return children.get(0).toString();
        }
    }
}
