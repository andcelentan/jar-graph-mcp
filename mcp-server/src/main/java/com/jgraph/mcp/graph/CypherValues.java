package com.jgraph.mcp.graph;

import org.neo4j.driver.Record;
import org.neo4j.driver.types.Entity;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns query results of any shape into plain Java values that serialize to JSON as is, like the
 * {@code List<Map<String, Object>>} of {@code JdbcTemplate.queryForList}. The driver's own objects for nodes,
 * relationships and paths are not plain: they become maps without Neo4j's internal ids, which mean nothing to an
 * assistant and change between databases.
 */
final class CypherValues {

    private CypherValues() {
    }

    /** One row, columns in the order of the query. */
    static Map<String, Object> toRow(Record record) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : record.keys()) {
            row.put(column, toPlain(record.get(column).asObject()));
        }
        return row;
    }

    /**
     * @param value a value as returned by {@code Value.asObject()}: lists and maps hold driver objects too, so the
     *              conversion is recursive
     */
    static Object toPlain(Object value) {
        return switch (value) {
            case null -> null;
            case String string -> string;
            case Boolean bool -> bool;
            case Long number -> number;
            case Double number -> number;
            case Node node -> nodeMap(node);
            case Relationship relationship -> relationshipMap(relationship);
            case Path path -> pathList(path);
            case List<?> list -> list.stream().map(CypherValues::toPlain).toList();
            case Map<?, ?> map -> plainMap(map);
            case byte[] bytes -> Base64.getEncoder().encodeToString(bytes);
            // Temporal values, durations, points: their ISO-like text form is what a reader expects
            default -> value.toString();
        };
    }

    private static Map<String, Object> nodeMap(Node node) {
        Map<String, Object> map = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        node.labels().forEach(labels::add);
        map.put("labels", labels);
        map.put("properties", properties(node));
        return map;
    }

    private static Map<String, Object> relationshipMap(Relationship relationship) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", relationship.type());
        map.put("properties", properties(relationship));
        return map;
    }

    /** Start node, then each relationship followed by the node it leads to. */
    private static List<Object> pathList(Path path) {
        List<Object> elements = new ArrayList<>();
        elements.add(nodeMap(path.start()));
        for (Path.Segment segment : path) {
            elements.add(relationshipMap(segment.relationship()));
            elements.add(nodeMap(segment.end()));
        }
        return elements;
    }

    private static Map<String, Object> properties(Entity entity) {
        return plainMap(entity.asMap());
    }

    private static Map<String, Object> plainMap(Map<?, ?> map) {
        Map<String, Object> plain = new LinkedHashMap<>();
        map.forEach((key, value) -> plain.put(String.valueOf(key), toPlain(value)));
        return plain;
    }
}
